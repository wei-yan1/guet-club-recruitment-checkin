#!/usr/bin/env python3
"""Export the source code submitted by the top students of OJ contests.

The script uses the public OJ API.  It does not save the session cookie in the
output file; pass it through OJ_SESSION_COOKIE or --cookie when running it.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from http.cookies import SimpleCookie
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


DEFAULT_BASE_URL = "https://oj.dsstudio.tech"
PAGE_SIZE = 100
REQUEST_TIMEOUT = 30
MAX_RETRIES = 3


class OjApiError(RuntimeError):
    pass


class OjClient:
    def __init__(self, base_url: str, cookie: str):
        self.base_url = base_url.rstrip("/")
        self.cookie = normalize_cookie(cookie)
        self.headers = {
            "Accept": "application/json",
            "User-Agent": "oj-top15-code-exporter/1.0",
            "Referer": self.base_url + "/",
            "Cookie": self.cookie,
        }
        csrf = csrf_from_cookie(self.cookie)
        if csrf:
            self.headers["X-CSRFToken"] = csrf

    def get_json(self, path: str, params: dict[str, Any]) -> dict[str, Any]:
        return self._request("GET", path, params=params)

    def post_json(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request("POST", path, payload=payload)

    def _request(
        self,
        method: str,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        payload: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        url = self.base_url + path
        if params:
            url += "?" + urlencode(params)

        body = None
        headers = dict(self.headers)
        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"

        last_error: Exception | None = None
        for attempt in range(MAX_RETRIES):
            try:
                request = Request(url, data=body, headers=headers, method=method)
                with urlopen(request, timeout=REQUEST_TIMEOUT) as response:
                    raw = response.read().decode("utf-8")
                result = json.loads(raw)
                if not isinstance(result, dict):
                    raise OjApiError(f"接口返回格式错误: {path}")
                if result.get("error") not in (None, ""):
                    raise OjApiError(f"接口返回错误: {result.get('data')}")
                return result
            except (HTTPError, URLError, TimeoutError, json.JSONDecodeError, OjApiError) as exc:
                last_error = exc
                if attempt + 1 < MAX_RETRIES:
                    time.sleep(1.5 * (attempt + 1))
        raise OjApiError(f"请求失败 {method} {path}: {last_error}") from last_error


def normalize_cookie(cookie: str) -> str:
    cookie = cookie.strip()
    if not cookie:
        raise ValueError("Cookie 不能为空")
    parts = []
    for part in cookie.split(";"):
        part = part.strip()
        if not part:
            continue
        parts.append(part if "=" in part else "sessionid=" + part)
    return "; ".join(parts)


def csrf_from_cookie(cookie: str) -> str:
    parsed = SimpleCookie()
    parsed.load(cookie)
    return parsed.get("csrftoken").value if parsed.get("csrftoken") else ""


def fetch_top_students(client: OjClient, contest_id: int, top_n: int) -> list[dict[str, Any]]:
    response = client.get_json(
        "/api/contest_rank/",
        {"contest_id": contest_id, "offset": 0, "limit": top_n},
    )
    rows = response.get("data", {}).get("results", [])
    if not isinstance(rows, list):
        raise OjApiError(f"比赛 {contest_id} 的排名数据格式错误")
    return rows[:top_n]


def fetch_all_submissions(client: OjClient, contest_id: int) -> list[dict[str, Any]]:
    submissions: list[dict[str, Any]] = []
    offset = 0
    total: int | None = None

    while total is None or offset < total:
        response = client.get_json(
            "/api/contest_submissions/",
            {"contest_id": contest_id, "offset": offset, "limit": PAGE_SIZE},
        )
        data = response.get("data", {})
        page = data.get("results", [])
        if not isinstance(page, list):
            raise OjApiError(f"比赛 {contest_id} 的提交数据格式错误")
        if total is None:
            total = int(data.get("total", 0))
        if not page:
            break
        submissions.extend(page)
        offset += len(page)
        if len(page) < PAGE_SIZE and offset < total:
            # Avoid an infinite loop if the server reports an incorrect total.
            break

    return submissions


def fetch_submission_detail(client: OjClient, submission_id: str) -> dict[str, Any]:
    response = client.get_json("/api/submission/", {"id": submission_id})
    data = response.get("data")
    if not isinstance(data, dict):
        raise OjApiError(f"提交 {submission_id} 的详情格式错误")
    return data


def compact_submission(row: dict[str, Any], detail: dict[str, Any]) -> dict[str, Any]:
    statistic = row.get("statistic_info") or {}
    return {
        "submission_id": row.get("id"),
        "create_time": row.get("create_time"),
        "language": row.get("language"),
        "result": row.get("result"),
        "score": statistic.get("score"),
        "code": detail.get("code"),
    }


def student_key(username: str) -> str:
    """Use the ten-digit student number as the JSON key when identifiable."""
    match = re.fullmatch(r"csd(\d{10})", username, flags=re.IGNORECASE)
    return match.group(1) if match else username


def export_contest(
    client: OjClient,
    contest_id: int,
    top_n: int,
) -> dict[str, Any]:
    ranking = fetch_top_students(client, contest_id, top_n)
    top_by_username: dict[str, tuple[int, dict[str, Any]]] = {}
    for rank, row in enumerate(ranking, start=1):
        username = row.get("user", {}).get("username")
        if username:
            top_by_username[username] = (rank, row)

    all_submissions = fetch_all_submissions(client, contest_id)
    selected = [
        row for row in all_submissions if row.get("username") in top_by_username
    ]

    students: dict[str, dict[str, Any]] = {}
    key_by_username: dict[str, str] = {}
    for username, (rank, row) in top_by_username.items():
        key = student_key(username)
        if key in students:
            key = username
        key_by_username[username] = key
        students[key] = {
            "student_number": key if key.isdigit() else None,
            "oj_username": username,
            "rank": rank,
            "total_score": row.get("total_score"),
            "problems": {},
        }

    failures: list[dict[str, str]] = []
    resolved: dict[int, tuple[dict[str, Any], dict[str, Any]]] = {}

    def load_detail(index_and_row: tuple[int, dict[str, Any]]) -> tuple[int, dict[str, Any], dict[str, Any]]:
        index, row = index_and_row
        submission_id = str(row.get("id", ""))
        if not submission_id:
            raise OjApiError("提交记录缺少提交 ID")
        return index, row, fetch_submission_detail(client, submission_id)

    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {
            pool.submit(load_detail, (index, row)): index
            for index, row in enumerate(selected)
        }
        for completed, future in enumerate(as_completed(futures), start=1):
            index = futures[future]
            try:
                _, row, detail = future.result()
                resolved[index] = (row, detail)
            except OjApiError as exc:
                sid = str(selected[index].get("id", ""))
                failures.append({"submission_id": sid, "reason": str(exc)})
            if completed % 20 == 0 or completed == len(selected):
                print(f"比赛 {contest_id}: 已处理 {completed}/{len(selected)} 条提交", file=sys.stderr)

    for index in sorted(resolved):
        row, detail = resolved[index]
        username = str(row.get("username", ""))
        problem = str(row.get("problem", ""))
        if not username or not problem:
            failures.append({"submission_id": str(row.get("id", "")), "reason": "提交记录字段不完整"})
            continue
        submission = compact_submission(row, detail)
        students[key_by_username[username]]["problems"].setdefault(problem, []).append(submission)

    return {
        "contest_id": contest_id,
        "top_n": top_n,
        "student_count": len(students),
        "submission_count": len(selected) - len(failures),
        "students": students,
        "failures": failures,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="导出 OJ 比赛前 N 名的全部提交代码")
    parser.add_argument("--base-url", default=os.getenv("OJ_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--contests", nargs="+", type=int, default=[52, 53])
    parser.add_argument("--top", type=int, default=15)
    parser.add_argument("--out", default="oj_top15_codes.json")
    parser.add_argument("--cookie", default=os.getenv("OJ_SESSION_COOKIE"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.top <= 0:
        raise SystemExit("--top 必须大于 0")
    cookie = args.cookie
    if not cookie:
        raise SystemExit("请设置 OJ_SESSION_COOKIE 环境变量，Cookie 不会写入 JSON")
    client = OjClient(args.base_url, cookie)

    result: dict[str, Any] = {
        "source": args.base_url,
        "contests": {},
    }
    for contest_id in args.contests:
        print(f"开始导出比赛 {contest_id}...", file=sys.stderr)
        result["contests"][str(contest_id)] = export_contest(
            client, contest_id, args.top
        )

    with open(args.out, "w", encoding="utf-8") as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(f"已写入 {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OjApiError, ValueError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        raise SystemExit(1)

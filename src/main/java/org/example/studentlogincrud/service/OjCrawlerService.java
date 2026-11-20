package org.example.studentlogincrud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OJ 分数爬虫：用已登录的会话 Cookie 调比赛排名接口，
 * 按 OJ 用户名（约定为学号，或绑定的 OJ 账号）匹配总分。
 * 支持上午（AM）/ 下午（PM）两场比赛；配置从 OjConfigService 动态读取（后台可改，保存即生效）。
 */
@Service
public class OjCrawlerService {

    private static final Logger log = LoggerFactory.getLogger(OjCrawlerService.class);
    /** 排名缓存时长：扫码查询频繁时避免反复请求 OJ */
    private static final long RANK_CACHE_TTL_MILLIS = 60_000L;

    /** 考试场次：上午场 / 下午场 */
    public enum Session { AM, PM }

    private record CachedRank(long fetchedAt, Map<String, BigDecimal> scores) {}

    private final OjConfigService configService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 已获得访问权限的比赛（密码赛只需提交一次） */
    private final Set<Long> contestAccessGranted = ConcurrentHashMap.newKeySet();
    /** 排名缓存：session → 用户名→总分 */
    private final Map<Session, CachedRank> rankCache = new ConcurrentHashMap<>();

    public OjCrawlerService(OjConfigService configService) {
        this.configService = configService;
    }

    /** 配置变更后调用：清空排名缓存与比赛访问记录，让新配置立即生效。 */
    public void invalidate() {
        rankCache.clear();
        contestAccessGranted.clear();
    }

    /** 爬虫是否启用：至少配置了一场比赛 id。 */
    public boolean isEnabled() {
        return contestIdOf(Session.AM) != null || contestIdOf(Session.PM) != null;
    }

    /** 某场比赛是否已配置。 */
    public boolean isSessionEnabled(Session session) {
        return contestIdOf(session) != null;
    }

    /** 场次对应的 OJ 比赛 id（未配置或非法返回 null）。 */
    public Long contestIdOf(Session session) {
        String key = session == Session.AM
                ? OjConfigService.KEY_CONTEST_ID_AM
                : OjConfigService.KEY_CONTEST_ID_PM;
        return parseContestId(configService.get(key));
    }

    private Long parseContestId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String config(String key) {
        String value = configService.get(key);
        return value == null ? "" : value.trim();
    }

    /** 按当前配置构建 RestClient（配置可在后台随时修改，不缓存客户端）。 */
    private RestClient client() {
        String baseUrl = config(OjConfigService.KEY_BASE_URL);
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        // 该 OJ 开启了 Django 严格 CSRF 校验：要求同源 Referer，否则全部 403
        if (!baseUrl.isEmpty()) {
            builder.defaultHeader("Referer", baseUrl + "/");
        }
        String cookie = config(OjConfigService.KEY_SESSION_COOKIE);
        if (!cookie.isEmpty()) {
            builder.defaultHeader("Cookie", normalizeCookie(cookie));
            String csrf = extractCsrfToken(cookie);
            if (!csrf.isEmpty()) {
                builder.defaultHeader("X-CSRFToken", csrf);
            }
        }
        return builder.build();
    }

    /**
     * 容忍用户只粘贴 sessionid 的值（如 5uxfks...）：
     * 形如 key=value 的片段原样保留，不含 "=" 的片段自动补上 sessionid= 前缀。
     */
    private String normalizeCookie(String cookie) {
        StringBuilder sb = new StringBuilder();
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            sb.append(trimmed.contains("=") ? trimmed : "sessionid=" + trimmed).append("; ");
        }
        String result = sb.toString();
        return result.endsWith("; ") ? result.substring(0, result.length() - 2) : result;
    }

    /**
     * 拉取某场比赛的 用户名 → 总分 映射（带 TTL 缓存）。
     * forceRefresh=true 跳过缓存强制刷新（后台手动同步用）。
     * 请求失败抛异常，由调用方决定回退策略。
     */
    public Map<String, BigDecimal> fetchRankScores(Session session, boolean forceRefresh) {
        Long contestId = contestIdOf(session);
        if (contestId == null) {
            return Map.of();
        }
        if (!forceRefresh) {
            CachedRank cached = rankCache.get(session);
            if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < RANK_CACHE_TTL_MILLIS) {
                return cached.scores();
            }
        }
        ensureContestAccess(session, contestId);

        // 排名接口是分页的（默认只返回前 10 条），用 limit+offset 循环拉取全部选手
        final int pageSize = 100;
        final int maxPages = 50; // 防御性上限，避免异常响应导致死循环
        Map<String, BigDecimal> scores = new HashMap<>();
        long total = Long.MAX_VALUE;
        int offset = 0;
        for (int page = 0; page < maxPages && scores.size() < total; page++) {
            String body = client().get()
                    .uri("/api/contest_rank/?contest_id=" + contestId + "&offset=" + offset + "&limit=" + pageSize)
                    .retrieve()
                    .body(String.class);
            JsonNode root = parseJson(body);
            if (root == null) {
                throw new IllegalStateException("OJ 排名接口无响应");
            }
            if (!root.path("error").isNull()) {
                throw new IllegalStateException("OJ 排名接口返回错误: " + root.path("data").asText());
            }
            JsonNode data = root.path("data");
            total = data.path("total").asLong(total);

            int fetched = 0;
            for (JsonNode row : data.path("results")) {
                fetched++;
                String username = row.path("user").path("username").asText("");
                String scoreText = row.path("total_score").asText("");
                if (username.isEmpty() || scoreText.isEmpty()) {
                    continue;
                }
                try {
                    scores.put(username, new BigDecimal(scoreText));
                } catch (NumberFormatException ignored) {
                    // 单条脏数据不影响整体
                }
            }
            if (fetched == 0) {
                break; // 没有更多数据
            }
            offset += fetched;
        }
        rankCache.put(session, new CachedRank(System.currentTimeMillis(), scores));
        return scores;
    }

    /** 按账号（OJ 账号或学号）查某场比赛的总分，匹配不到返回 null。 */
    public BigDecimal fetchScore(Session session, String account) {
        if (account == null || account.trim().isEmpty()) {
            return null;
        }
        return fetchRankScores(session, false).get(account.trim());
    }

    /** 进入密码保护的比赛：先查访问状态，没权限就用密码提交（按比赛分别记录）。 */
    private void ensureContestAccess(Session session, Long contestId) {
        if (contestAccessGranted.contains(contestId)) {
            return;
        }
        try {
            String accessBody = client().get()
                    .uri("/api/contest/access/?contest_id=" + contestId)
                    .retrieve()
                    .body(String.class);
            JsonNode access = parseJson(accessBody);
            if (access != null && access.path("data").path("access").asBoolean(false)) {
                contestAccessGranted.add(contestId);
                return;
            }
            String passwordKey = session == Session.AM
                    ? OjConfigService.KEY_CONTEST_PASSWORD_AM
                    : OjConfigService.KEY_CONTEST_PASSWORD_PM;
            String contestPassword = config(passwordKey);
            if (contestPassword.isEmpty()) {
                log.warn("OJ 比赛 {} 需要密码，但未配置比赛密码", contestId);
                return;
            }
            String payload = objectMapper.writeValueAsString(
                    Map.of("contest_id", contestId, "password", contestPassword));
            String passwordBody = client().post()
                    .uri("/api/contest/password/")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            JsonNode passwordResult = parseJson(passwordBody);
            if (passwordResult != null && passwordResult.path("error").isNull()) {
                contestAccessGranted.add(contestId);
            } else {
                log.warn("OJ contest {} password verification failed: {}", contestId,
                        passwordResult == null ? "invalid response" : passwordResult.path("data").asText());
            }
        } catch (Exception e) {
            log.warn("进入 OJ 比赛 {} 失败: {}", contestId, e.getMessage());
        }
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("解析 OJ 响应失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractCsrfToken(String cookie) {
        for (String part : cookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && "csrftoken".equalsIgnoreCase(kv[0])) {
                return kv[1];
            }
        }
        return "";
    }
}

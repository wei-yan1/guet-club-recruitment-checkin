package org.example.studentlogincrud.controller;

import org.example.studentlogincrud.dto.OjConfigPayload;
import org.example.studentlogincrud.entity.Result;
import org.example.studentlogincrud.service.OjConfigService;
import org.example.studentlogincrud.service.OjCrawlerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** OJ 爬虫配置：管理后台可视化编辑，保存后立即生效（无需重启）。 */
@RestController
@RequestMapping("/api/admin/oj-config")
public class OjConfigController {

    private final OjConfigService configService;
    private final OjCrawlerService crawlerService;

    public OjConfigController(OjConfigService configService, OjCrawlerService crawlerService) {
        this.configService = configService;
        this.crawlerService = crawlerService;
    }

    @GetMapping
    public Result<OjConfigPayload> get() {
        return Result.success(toPayload());
    }

    @PutMapping
    public Result<OjConfigPayload> update(@RequestBody OjConfigPayload payload) {
        if (payload == null) {
            return Result.error(400, "Configuration is required");
        }
        String baseUrl = orEmpty(payload.getBaseUrl());
        if (!baseUrl.isEmpty() && !baseUrl.matches("^https?://.+$")) {
            return Result.error(400, "Base URL must start with http:// or https://");
        }
        String idAm = orEmpty(payload.getContestIdAm());
        String idPm = orEmpty(payload.getContestIdPm());
        if (!idAm.isEmpty() && !idAm.matches("\\d+")) {
            return Result.error(400, "Morning contest id must be numeric");
        }
        if (!idPm.isEmpty() && !idPm.matches("\\d+")) {
            return Result.error(400, "Afternoon contest id must be numeric");
        }

        Map<String, String> values = new HashMap<>();
        values.put(OjConfigService.KEY_BASE_URL, baseUrl);
        values.put(OjConfigService.KEY_CONTEST_ID_AM, idAm);
        values.put(OjConfigService.KEY_CONTEST_ID_PM, idPm);
        String passwordAm = payload.getContestPasswordAm();
        String passwordPm = payload.getContestPasswordPm();
        if (passwordAm == null && passwordPm == null && payload.getContestPassword() != null) {
            passwordAm = payload.getContestPassword();
            passwordPm = payload.getContestPassword();
        }
        values.put(OjConfigService.KEY_CONTEST_PASSWORD_AM, orEmpty(passwordAm));
        values.put(OjConfigService.KEY_CONTEST_PASSWORD_PM, orEmpty(passwordPm));
        values.put(OjConfigService.KEY_SESSION_COOKIE, orEmpty(payload.getSessionCookie()));
        configService.update(values);
        crawlerService.invalidate();
        return Result.success(toPayload());
    }

    private OjConfigPayload toPayload() {
        Map<String, String> config = configService.getAll();
        OjConfigPayload payload = new OjConfigPayload();
        payload.setBaseUrl(config.getOrDefault(OjConfigService.KEY_BASE_URL, ""));
        payload.setContestIdAm(config.getOrDefault(OjConfigService.KEY_CONTEST_ID_AM, ""));
        payload.setContestIdPm(config.getOrDefault(OjConfigService.KEY_CONTEST_ID_PM, ""));
        payload.setContestPasswordAm(config.getOrDefault(OjConfigService.KEY_CONTEST_PASSWORD_AM, ""));
        payload.setContestPasswordPm(config.getOrDefault(OjConfigService.KEY_CONTEST_PASSWORD_PM, ""));
        payload.setSessionCookie(config.getOrDefault(OjConfigService.KEY_SESSION_COOKIE, ""));
        return payload;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}

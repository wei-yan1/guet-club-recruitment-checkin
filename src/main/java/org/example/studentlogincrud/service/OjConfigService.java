package org.example.studentlogincrud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.example.studentlogincrud.entity.AppConfig;
import org.example.studentlogincrud.mapper.AppConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时可编辑配置（存 app_config 表）：
 * application.properties 只提供首次默认值（种子），管理后台保存后立即生效。
 */
@Service
public class OjConfigService extends ServiceImpl<AppConfigMapper, AppConfig> {

    private static final Logger log = LoggerFactory.getLogger(OjConfigService.class);

    public static final String KEY_BASE_URL = "oj.base-url";
    public static final String KEY_CONTEST_ID_AM = "oj.contest-id-am";
    public static final String KEY_CONTEST_ID_PM = "oj.contest-id-pm";
    public static final String KEY_CONTEST_PASSWORD = "oj.contest-password";
    public static final String KEY_CONTEST_PASSWORD_AM = "oj.contest-password-am";
    public static final String KEY_CONTEST_PASSWORD_PM = "oj.contest-password-pm";
    public static final String KEY_SESSION_COOKIE = "oj.session-cookie";

    private static final List<String> KEYS = List.of(
            KEY_BASE_URL, KEY_CONTEST_ID_AM, KEY_CONTEST_ID_PM,
            KEY_CONTEST_PASSWORD_AM, KEY_CONTEST_PASSWORD_PM, KEY_SESSION_COOKIE);

    private final Map<String, String> propertyDefaults;

    private volatile Map<String, String> cache;

    public OjConfigService(
            @Value("${oj.base-url:}") String baseUrl,
            @Value("${oj.contest-id-am:}") String contestIdAm,
            @Value("${oj.contest-id-pm:}") String contestIdPm,
            @Value("${oj.contest-password-am:}") String contestPasswordAm,
            @Value("${oj.contest-password-pm:}") String contestPasswordPm,
            @Value("${oj.contest-password:}") String legacyContestPassword,
            @Value("${oj.session-cookie:}") String sessionCookie) {
        propertyDefaults = Map.of(
                KEY_BASE_URL, safe(baseUrl),
                KEY_CONTEST_ID_AM, safe(contestIdAm),
                KEY_CONTEST_ID_PM, safe(contestIdPm),
                KEY_CONTEST_PASSWORD_AM, safe(contestPasswordAm).isEmpty() ? safe(legacyContestPassword) : safe(contestPasswordAm),
                KEY_CONTEST_PASSWORD_PM, safe(contestPasswordPm).isEmpty() ? safe(legacyContestPassword) : safe(contestPasswordPm),
                KEY_SESSION_COOKIE, safe(sessionCookie));
    }

    /** 全部配置（DB 值优先，缺省回退 application.properties）。 */
    public Map<String, String> getAll() {
        Map<String, String> snapshot = cache;
        return snapshot != null ? snapshot : refreshCache();
    }

    public String get(String key) {
        return getAll().getOrDefault(key, "");
    }

    /** 保存配置（空字符串表示清空该项），保存后立即生效。 */
    public synchronized Map<String, String> update(Map<String, String> values) {
        Map<String, String> next = new HashMap<>();
        for (String key : KEYS) {
            String value = values.get(key);
            value = value == null ? "" : value.trim();
            AppConfig row = getOne(new LambdaQueryWrapper<AppConfig>().eq(AppConfig::getConfigKey, key));
            if (row == null) {
                row = new AppConfig();
                row.setConfigKey(key);
                row.setConfigValue(value);
                save(row);
            } else {
                row.setConfigValue(value);
                updateById(row);
            }
            next.put(key, value);
        }
        cache = next;
        return next;
    }

    private synchronized Map<String, String> refreshCache() {
        Map<String, String> loaded = new HashMap<>(propertyDefaults);
        try {
            Map<String, String> fromDb = new HashMap<>();
            String legacyContestPassword = "";
            for (AppConfig row : list()) {
                if (KEY_CONTEST_PASSWORD.equals(row.getConfigKey()) && row.getConfigValue() != null) {
                    legacyContestPassword = row.getConfigValue();
                } else if (row.getConfigKey() != null && KEYS.contains(row.getConfigKey()) && row.getConfigValue() != null) {
                    fromDb.put(row.getConfigKey(), row.getConfigValue());
                }
            }
            for (String key : KEYS) {
                if (!fromDb.containsKey(key)) {
                    AppConfig row = new AppConfig();
                    row.setConfigKey(key);
                    String legacyValue = legacyContestPassword;
                    String fallback = (KEY_CONTEST_PASSWORD_AM.equals(key) || KEY_CONTEST_PASSWORD_PM.equals(key))
                            && !legacyValue.isBlank() ? legacyValue : propertyDefaults.get(key);
                    row.setConfigValue(fallback);
                    save(row);
                    fromDb.put(key, fallback);
                }
            }
            loaded.putAll(fromDb);
        } catch (Exception e) {
            log.warn("app_config 表不可用，使用 application.properties 默认值: {}", e.getMessage());
        }
        cache = loaded;
        return loaded;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

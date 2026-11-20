package org.example.studentlogincrud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.example.studentlogincrud.entity.AppConfig;
import org.example.studentlogincrud.entity.Student;
import org.example.studentlogincrud.mapper.AppConfigMapper;
import org.example.studentlogincrud.mapper.StudentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * OJ 账号发号器：从 csd2026001 开始，每保存一个学生加一。
 * 计数存 app_config（key = oj.account-seq）；
 * 首次使用（或计数缺失）时从已有学生数据中最大的编号接着计数，避免删号后重发。
 */
@Service
public class OjAccountService extends ServiceImpl<AppConfigMapper, AppConfig> {

    private static final Logger log = LoggerFactory.getLogger(OjAccountService.class);

    /** 账号前缀 */
    public static final String PREFIX = "csd2026";
    /** app_config 里存计数的 key（不在 OjConfigService.KEYS 白名单内，配置弹窗不会动它） */
    private static final String SEQ_KEY = "oj.account-seq";

    private final StudentMapper studentMapper;

    public OjAccountService(StudentMapper studentMapper) {
        this.studentMapper = studentMapper;
    }

    /** 生成下一个 OJ 账号（如 csd2026001），并推进计数。 */
    public synchronized String next() {
        int next = currentSeq() + 1;
        saveSeq(next);
        String account = PREFIX + String.format("%03d", next);
        log.info("分配 OJ 账号: {}", account);
        return account;
    }

    private int currentSeq() {
        AppConfig row = getOne(new LambdaQueryWrapper<AppConfig>().eq(AppConfig::getConfigKey, SEQ_KEY));
        if (row != null && row.getConfigValue() != null && !row.getConfigValue().isBlank()) {
            try {
                return Integer.parseInt(row.getConfigValue().trim());
            } catch (NumberFormatException ignored) {
                log.warn("app_config 中 {} 不是数字，改为按已有数据重新计号", SEQ_KEY);
            }
        }
        // 首次使用（或计数损坏）：扫描已有学生的 csd2026 账号取最大编号
        int max = 0;
        for (Student student : studentMapper.selectList(
                new LambdaQueryWrapper<Student>().likeRight(Student::getOjAccount, PREFIX))) {
            String tail = student.getOjAccount().substring(PREFIX.length());
            try {
                max = Math.max(max, Integer.parseInt(tail));
            } catch (NumberFormatException ignored) {
                // 非本格式的账号不参与计数
            }
        }
        return max;
    }

    private void saveSeq(int seq) {
        AppConfig row = getOne(new LambdaQueryWrapper<AppConfig>().eq(AppConfig::getConfigKey, SEQ_KEY));
        if (row == null) {
            row = new AppConfig();
            row.setConfigKey(SEQ_KEY);
            row.setConfigValue(String.valueOf(seq));
            save(row);
        } else {
            update(new LambdaUpdateWrapper<AppConfig>()
                    .eq(AppConfig::getId, row.getId())
                    .set(AppConfig::getConfigValue, String.valueOf(seq)));
        }
    }
}

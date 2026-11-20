package org.example.studentlogincrud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 运行时可编辑的键值配置（如 OJ 爬虫配置），管理后台保存后立即生效。 */
@Data
@TableName("app_config")
public class AppConfig {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("config_key")
    private String configKey;

    @TableField("config_value")
    private String configValue;
}

package org.example.studentlogincrud.dto;

import lombok.Data;

/** OJ 爬虫配置（管理后台读取/保存用）。空字符串表示未设置。 */
@Data
public class OjConfigPayload {
    /** OJ 网站地址，如 https://oj.example.com */
    private String baseUrl;
    /** 上午场比赛 ID（留空关闭上午场） */
    private String contestIdAm;
    /** 下午场比赛 ID（留空关闭下午场） */
    private String contestIdPm;
    /** 上午场密码保护比赛的密码（无则留空） */
    private String contestPasswordAm;
    /** 下午场密码保护比赛的密码（无则留空） */
    private String contestPasswordPm;
    /** 兼容旧版单密码客户端 */
    @Deprecated
    private String contestPassword;
    /** 登录 OJ 后的 Cookie，如 sessionid=xxx; csrftoken=xxx */
    private String sessionCookie;
}

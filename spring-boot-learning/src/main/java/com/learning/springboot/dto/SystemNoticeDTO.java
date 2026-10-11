package com.learning.springboot.dto;

/**
 * 管理员发布系统通知入参(推送给全体启用用户)
 */
public class SystemNoticeDTO {

    private String title;
    private String content;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}

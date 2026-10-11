package com.learning.springboot.dto;

/**
 * 用户反馈入参(提交用)
 * <p>
 * RATING 类型: score 必填(1~5);BUG 类型: title/content 描述问题。
 */
public class FeedbackDTO {

    private String type;
    private Integer score;
    private String title;
    private String content;
    private String contact;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }
}

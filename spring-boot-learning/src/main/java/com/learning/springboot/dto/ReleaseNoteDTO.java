package com.learning.springboot.dto;

import java.time.LocalDate;

/**
 * 版本更新公告入参(新增/编辑共用)
 */
public class ReleaseNoteDTO {

    private String version;
    private String title;
    private String content;
    private LocalDate releaseDate;

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDate getReleaseDate() { return releaseDate; }
    public void setReleaseDate(LocalDate releaseDate) { this.releaseDate = releaseDate; }
}

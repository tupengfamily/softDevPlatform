package com.learning.springboot.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 个人信息更新请求(本人自助修改)
 * <p>
 * 全部字段可选:为 null 表示不修改。
 * 用户名/密码/角色不在本接口范围(用户名不可改,密码走独立流程)。
 */
public class ProfileUpdateRequest {

    @Size(max = 50, message = "昵称长度不能超过 50")
    private String nickname;

    /** 头像:图片 URL 或 emoji 字符 */
    @Size(max = 500, message = "头像地址过长")
    private String avatar;

    @Size(max = 200, message = "个性签名不能超过 200 字")
    private String signature;

    @Email(message = "邮箱格式错误")
    @Size(max = 100, message = "邮箱长度不能超过 100")
    private String email;

    @Min(value = 0, message = "年龄不合法")
    @Max(value = 150, message = "年龄不合法")
    private Integer age;

    @Size(max = 200, message = "地址长度不能超过 200")
    private String address;

    public ProfileUpdateRequest() {}

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
}

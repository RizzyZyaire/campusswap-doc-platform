package com.campusswap.system.vo;

import com.campusswap.system.vo.UserInfoVo;
import java.util.List;

/**
 * 登录出参（API_SPECIFICATION §4.1.1，GLOSSARY §3.7 的 {@code LoginVo}）。
 *
 * <p>{@code roles} / {@code permissions} 与 {@code userInfo} 内的同名集合同值，
 * 便于前端直接取用而不必层层解包。</p>
 *
 * @param token       访问令牌（放 {@code Authorization: Bearer}，有效期 2 小时）
 * @param userInfo    用户信息
 * @param roles       角色编码集合
 * @param permissions 权限码集合
 * @author Zyaire
 */
public record LoginVo(String token, UserInfoVo userInfo, List<String> roles, List<String> permissions) {
}

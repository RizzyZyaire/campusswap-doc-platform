package com.campusswap.system.dto;

import com.campusswap.entity.enums.UserStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 停用/启用用户入参（API_SPECIFICATION §4.2.5）。
 *
 * @param status 目标状态：ACTIVE / LOCKED / DISABLED
 * @author Zyaire
 */
public record UserStatusDtoReq(

        @NotNull(message = "账号状态取值非法，仅支持 ACTIVE、LOCKED、DISABLED")
        UserStatus status) {
}

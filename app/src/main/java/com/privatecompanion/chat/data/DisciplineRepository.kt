package com.privatecompanion.chat.data

import com.privatecompanion.chat.model.DisciplineProfile

/**
 * 自律监督状态入口。
 * 后续可接 UsageStatsManager/AccessibilityService 实现 App 拦截。
 */
class DisciplineRepository {
    private var profile = DisciplineProfile()
    fun get(): DisciplineProfile = profile
    fun update(value: DisciplineProfile) { profile = value }

    /** 用户主动使用最高权限解除任务，每周次数限制由本地程序控制。 */
    fun useOverride(): Boolean {
        if (profile.overrideTokens <= 0) return false
        if (profile.weeklyOverrideUsed >= profile.weeklyOverrideLimit) return false
        profile = profile.copy(
            active = false,
            overrideTokens = profile.overrideTokens - 1,
            weeklyOverrideUsed = profile.weeklyOverrideUsed + 1
        )
        return true
    }

    fun canOverride(): Boolean = profile.overrideTokens > 0 && profile.weeklyOverrideUsed < profile.weeklyOverrideLimit
}

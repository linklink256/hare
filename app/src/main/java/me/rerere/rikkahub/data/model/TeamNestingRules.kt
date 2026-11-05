package me.rerere.rikkahub.data.model

/** 派生决策：Allowed 才会真的建队友，Rejected 带一句给人看的拒绝理由 */
sealed interface SpawnPlan {
    data object Allowed : SpawnPlan
    data class Rejected(val message: String) : SpawnPlan
}

/** 团队嵌套深度门禁（纯逻辑，可脱离 Android 单测） */
object TeamNestingRules {
    /** 默认不开放嵌套：成员一层都建不了，等价 R5-D 之前的行为 */
    const val DEFAULT_MAX_DEPTH = 1

    /** 设置页允许配置的上限 */
    const val MAX_SUPPORTED_DEPTH = 3

    /** 旧名，仅为兼容既有引用保留；新代码用 [DEFAULT_MAX_DEPTH] */
    @Deprecated("use DEFAULT_MAX_DEPTH", ReplaceWith("TeamNestingRules.DEFAULT_MAX_DEPTH"))
    const val MAX_DEPTH = DEFAULT_MAX_DEPTH

    /** 把设置里的原始值夹到 1..[MAX_SUPPORTED_DEPTH] */
    fun normalizeMaxDepth(raw: Int): Int = raw.coerceIn(1, MAX_SUPPORTED_DEPTH)

    /** 调用者若是某个团队的成员，它就是更深一层的 lead */
    fun depthOf(callerIsMember: Boolean, parentDepth: Int = 0): Int =
        if (callerIsMember) parentDepth + 1 else 0

    /** 成员在当前深度再建一层后仍小于 [maxDepth]（即新子队不会顶到上限）才允许 */
    fun canNest(parentDepth: Int, maxDepth: Int): Boolean = parentDepth + 1 < maxDepth

    /** 只在真正建队/加成员的服务入口调用：根 lead 恒可建，成员受深度门禁 */
    fun planSpawn(callerIsMember: Boolean, currentDepth: Int, maxDepth: Int = DEFAULT_MAX_DEPTH): SpawnPlan =
        if (!callerIsMember || canNest(currentDepth, maxDepth)) {
            SpawnPlan.Allowed
        } else {
            SpawnPlan.Rejected(
                "nested teams are disabled (max depth $maxDepth); ask the lead to create the teammate"
            )
        }
}

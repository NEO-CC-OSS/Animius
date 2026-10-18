package com.lanlinju.animius.domain.model

/**
 * 应用内更新的「下载阶段」状态。
 *
 * 说明：检查更新阶段维持原有行为（主页自动检查静默、时间表页手动检查有独立提示），
 * 不纳入本模型；本模型只描述用户点击「更新」之后的流程，用于驱动模态进度对话框。
 */
sealed interface UpdateState {

    /** 空闲：没有进行中的更新（对话框显示版本说明，等待用户选择） */
    object Idle : UpdateState

    /** 已入队、等待系统调度（Android 12+ 加急任务配额被延迟时会出现，属正常表现） */
    object Preparing : UpdateState

    /**
     * 下载中。
     * @param progress 0f~1f；为 null 表示服务端未提供总长度（如部分镜像通道），此时只显示已下载字节数
     * @param downloadedBytes 已下载字节数
     */
    data class Downloading(val progress: Float?, val downloadedBytes: Long) : UpdateState

    /** 下载完成，正在校验安装包（哈希 + 签名） */
    object Verifying : UpdateState

    /** 失败，需用户选择重试或关闭；[detail] 为诊断用的异常摘要 */
    data class Failed(val reason: FailReason, val detail: String? = null) : UpdateState

    /** 用户主动取消 */
    object Cancelled : UpdateState

    /** 下载与校验通过，已调起系统安装器 */
    object Completed : UpdateState

    enum class FailReason {
        /** 所有下载通道都无法连接 */
        Network,

        /** 安装包校验（体积/哈希）未通过 */
        Checksum,

        /** 安装包签名与应用不一致 */
        Signature,

        /** 无法调起系统安装器（部分电视系统缺少安装组件） */
        Install,

        /** 其他未预期错误 */
        Unknown,
    }
}

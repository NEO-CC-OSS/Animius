package com.lanlinju.animius.parse

import com.lanlinju.animius.data.remote.parse.isPlayableMediaUrl
import com.lanlinju.animius.data.remote.parse.playPageUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Age动漫取址健壮化（2026-10-22）的纯函数测试：
 *
 * 1. [isPlayableMediaUrl]：取址结果必须是 `.m3u8` / `.mp4`，脚本/样式/页面一律判失败
 *    （实测踩坑：`/obj/` 命中对象存储路径，把 `.play.min.js` 当视频返回，播放器 0.29s 后
 *    报 `UnrecognizedInputFormatException`）。
 * 2. [playPageUrls]：加密线路（`/vip/`）时顺延同条目 line+1..+3 的播放页，找明文 `/m3u8/` 线路。
 */
class AgedmPlayUrlTest {

    private val base = "https://www.agedm.io"

    // ---------- isPlayableMediaUrl ----------

    @Test
    fun `明文 m3u8 通过`() {
        assertTrue(
            isPlayableMediaUrl("https://vip.ffzy-plays.com/20261011/59377_7faa7ab7/index.m3u8")
        )
    }

    @Test
    fun `带 query 的 m3u8 通过`() {
        assertTrue(isPlayableMediaUrl("https://cdn.example.com/hls/index.m3u8?token=abc&t=1"))
    }

    @Test
    fun `mp4 通过`() {
        assertTrue(isPlayableMediaUrl("https://cdn.example.com/vod/1.mp4"))
    }

    @Test
    fun `播放器脚本 play min js 拒绝`() {
        // 用户实测被送进播放器的地址（对象存储 + .js）
        assertFalse(
            isPlayableMediaUrl(
                "https://p3-tt.byteimg.com/obj/tos-cn-i-jcdsk5yqko/" +
                    "7e38a70a1d014abe8d24a7fc2b456477?.play.min.js"
            )
        )
    }

    @Test
    fun `css 拒绝`() {
        assertFalse(isPlayableMediaUrl("https://www.agedm.io/static/player.css"))
    }

    @Test
    fun `html 拒绝`() {
        assertFalse(isPlayableMediaUrl("https://www.agedm.io/play/20260303/1/1.html"))
    }

    @Test
    fun `空串拒绝`() {
        assertFalse(isPlayableMediaUrl(""))
        assertFalse(isPlayableMediaUrl("   "))
    }

    // ---------- playPageUrls ----------

    @Test
    fun `线路顺延生成 3 条且 baseUrl 拼接正确`() {
        val urls = playPageUrls("/play/20260303/1/1", base)
        assertEquals(
            listOf(
                "https://www.agedm.io/play/20260303/2/1",
                "https://www.agedm.io/play/20260303/3/1",
                "https://www.agedm.io/play/20260303/4/1",
            ),
            urls,
        )
    }

    @Test
    fun `baseUrl 带尾斜杠也能拼对`() {
        assertEquals(
            listOf("https://www.agedm.io/play/20260303/2/1"),
            playPageUrls("/play/20260303/1/1", "https://www.agedm.io/", maxExtraLines = 1),
        )
    }

    @Test
    fun `相对路径与尾斜杠同样能解析`() {
        val urls = playPageUrls("play/20260303/5/12/", base, maxExtraLines = 2)
        assertEquals(
            listOf(
                "https://www.agedm.io/play/20260303/6/12",
                "https://www.agedm.io/play/20260303/7/12",
            ),
            urls,
        )
    }

    @Test
    fun `非法输入返回空列表`() {
        assertTrue(playPageUrls("", base).isEmpty())
        assertTrue(playPageUrls("/detail/636023", base).isEmpty())
        assertTrue(playPageUrls("/play/20260303/abc/1", base).isEmpty())
        assertTrue(playPageUrls("/play/20260303/1/1", base, maxExtraLines = 0).isEmpty())
    }
}

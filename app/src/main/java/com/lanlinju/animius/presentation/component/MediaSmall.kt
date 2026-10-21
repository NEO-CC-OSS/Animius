package com.lanlinju.animius.presentation.component

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lanlinju.animius.util.CROSSFADE_DURATION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.lanlinju.animius.R as Res

/**
 * A [LazyRow] of [MediaSmall]s.
 *
 * @param mediaList A list of [HomeItem]s.
 */
@Composable
fun <T> MediaSmallRow(
    mediaList: List<T>,
    // contentIndexed 为可选变体；content 必须保持最后一个参数，详情页等调用方使用尾随 lambda
    contentIndexed: @Composable ((Int, T) -> Unit)? = null,
    content: @Composable (T) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(Res.dimen.small_padding)),
        contentPadding = PaddingValues(
            start = dimensionResource(Res.dimen.large_padding),
            end = dimensionResource(Res.dimen.large_padding)
        ),
        // TV：行内左右到边界就卡住，防止焦点跳进 HorizontalPager 邻页的行（换行用上下键）
        modifier = Modifier.focusProperties {
            left = FocusRequester.Cancel
            right = FocusRequester.Cancel
        }
    ) {
        if (contentIndexed != null) {
            itemsIndexed(mediaList) { index, media -> contentIndexed(index, media) }
        } else {
            items(mediaList) { media -> content(media) }
        }
    }
}

/**
 * A [Card] to display a media image and a label.
 *
 * @param image A URL of the image to be shown in the card that this component is.
 * @param label A label for the [image], if this is `null`, the [label] is not shown.
 * @param onClick Action to happen when the card is clicked.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaSmall(
    image: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    onSuccess: (Bitmap) -> Unit = { },
    /* 元数据轻包（schema 2）：第二级回退（jsDelivr 备用域名）；为空跳过 */
    backupImage: String? = null,
    /* 元数据轻包（schema 2）：最后一级回退（gh-proxy）；为空跳过 */
    fallbackImage: String? = null,
    /* 元数据轻包（schema 2）：加载中的模糊占位图（data URI）；为空不显示 */
    lqip: String? = null,
) {
    MediaSmall(
        modifier = modifier
            .tvFocus(shape = RoundedCornerShape(dimensionResource(Res.dimen.media_card_corner_radius)))
            .clip(RoundedCornerShape(dimensionResource(Res.dimen.media_card_corner_radius)))
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        image = image,
        label = label,
        onSuccess = onSuccess,
        backupImage = backupImage,
        fallbackImage = fallbackImage,
        lqip = lqip,
    )
}

@Composable
fun MediaSmall(
    image: String?,
    modifier: Modifier = Modifier,
    label: String? = null,
    onSuccess: (Bitmap) -> Unit = { },
    backupImage: String? = null,
    fallbackImage: String? = null,
    lqip: String? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(dimensionResource(Res.dimen.media_card_corner_radius)),
    ) {
        val imageModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .clip(RoundedCornerShape(dimensionResource(Res.dimen.media_card_corner_radius)))
        if (image.isNullOrBlank()) {
            // 无封面（如 Age动漫 周表：站点本身不提供图）→ 直接画占位底图，避免留白
            CoverPlaceholder(label = label, modifier = imageModifier)
        } else if (image.startsWith("data:image/")) {
            // 周表元数据的封面是内嵌 base64（Coil 2.x 不支持 data URI）；
            // 后台解码 + 有界缓存，避免主线程解码和滚动回来重复解码
            val bitmap = rememberDecodedCover(image)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = imageModifier,
                )
            } else {
                CoverPlaceholder(label = label, modifier = imageModifier)
            }
        } else {
            // 轻包封面三级加载：jsDelivr 主域名 → jsDelivr 备用域名 → gh-proxy；
            // 加载中先显示 LQIP 模糊图（竖版、比例一致），加载成功后才撤掉
            val sources = remember(image, backupImage, fallbackImage) {
                listOfNotNull(image, backupImage, fallbackImage)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            }
            var stage by remember(image, backupImage, fallbackImage) { mutableIntStateOf(0) }
            var loaded by remember(image, backupImage, fallbackImage) { mutableStateOf(false) }
            // LQIP 必须一直垫在清晰图下面，直到 crossfade 真正结束：
            // onSuccess 触发时渐显才刚开始，此时撤掉 LQIP 会露出 500 ms 卡片底色（模糊图→底色→清晰图）。
            // 加载失败时 loaded 永远为 false → LQIP 保留，不露底色。
            var lqipSettled by remember(image, backupImage, fallbackImage) { mutableStateOf(false) }
            LaunchedEffect(loaded) {
                if (loaded) {
                    delay(CROSSFADE_DURATION.toLong())
                    lqipSettled = true
                }
            }
            val model = sources.getOrElse(stage) { image }
            val lqipBitmap = if (!lqipSettled) {
                lqip?.takeIf { it.isNotBlank() }?.let { rememberDecodedCover(it) }
            } else {
                null
            }
            Box(modifier = imageModifier) {
                if (lqipBitmap != null) {
                    Image(
                        bitmap = lqipBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(model)
                        .crossfade(CROSSFADE_DURATION)
                        .build(),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    // 不设 placeholder —— background.png 是横版，用 ContentScale.Crop 填竖版卡片
                    // 会被大幅裁剪放大，视觉跳变明显；竖版模糊图改用 LQIP 叠在下面。
                    // 有 LQIP 时失败态保留模糊图（不再用横版底图把它盖住）；没有 LQIP 才用底图兜底。
                    error = if (lqip.isNullOrBlank()) painterResource(Res.drawable.background) else null,
                    modifier = Modifier.fillMaxSize(),
                    onError = {
                        // 依次尝试备用域名 / gh-proxy；都失败就停在最后一级，由 error/LQIP 兜底
                        if (stage < sources.lastIndex) stage += 1
                    },
                    onSuccess = {
                        loaded = true
                        // 某些来源返回非位图（如矢量/GIF）时强转会崩，取不到位图就跳过取色回调
                        val bitmap = (it.result.drawable as? BitmapDrawable)?.bitmap
                        if (bitmap != null) onSuccess(bitmap)
                    }
                )
            }
        }

        if (label != null)
            Box {
                Text(
                    text = " \n ",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    modifier = Modifier.padding(
                        vertical = dimensionResource(Res.dimen.media_card_text_padding_vertical)
                    )
                )

                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    // TODO: Add a custom overflow indicator:
                    //  https://proandroiddev.com/detect-text-overflow-in-jetpack-compose-56c0b83da5a5.
                    overflow = TextOverflow.Visible,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(
                            horizontal = dimensionResource(Res.dimen.media_card_text_padding_horizontal),
                            vertical = dimensionResource(Res.dimen.media_card_text_padding_vertical)
                        )
                )
            }
    }
}

@Composable
private fun CoverPlaceholder(label: String?, modifier: Modifier) {
    Image(
        painter = painterResource(Res.drawable.background),
        contentDescription = label,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/** 有界封面缓存：320×448 RGB_565 约 280 KB/张，16 MB 约 58 张 */
private object CoverCache : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

@Composable
private fun rememberDecodedCover(dataUri: String): Bitmap? {
    val cached = remember(dataUri) { CoverCache.get(dataUri) }
    var bitmap by remember(dataUri) { mutableStateOf(cached) }
    LaunchedEffect(dataUri) {
        if (cached == null) {
            val decoded = withContext(Dispatchers.Default) { decodeBase64Image(dataUri) }
            if (decoded != null) {
                CoverCache.put(dataUri, decoded)
            }
            bitmap = decoded
        }
    }
    return bitmap
}

/** 解码 "data:image/webp;base64,..."；失败返回 null，由调用方显示占位图 */
private fun decodeBase64Image(dataUri: String): Bitmap? = try {
    val comma = dataUri.indexOf(',')
    if (comma <= 0) {
        null
    } else {
        val bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
        // 封面不透明，RGB_565 省一半内存，电视卡片上看不出差别
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
} catch (_: Exception) {
    null
}

@Preview
@Composable
fun PreviewMediaSmall() {
    MediaSmall(
        image =
        "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx132405-qP7FQYGmNI3d.jpg",
        label =
        "Sono Bisque Doll wa Koi wo Suru",
        onClick = { },
        modifier = Modifier.width(dimensionResource(Res.dimen.media_card_width))
    )
}
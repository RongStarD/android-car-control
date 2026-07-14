package cn.edu.xxq.rosmastercontrol

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatOrderTime(
    value: String?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val clean = value?.trim()?.takeIf { it.isNotEmpty() } ?: return "时间未记录"
    val instant = runCatching { Instant.parse(clean) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(clean).toInstant() }.getOrNull()
    if (instant != null) {
        return DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .withZone(zoneId)
            .format(instant)
    }
    return clean.replace('T', ' ').removeSuffix("Z").take(19).ifBlank { "时间未记录" }
}

package com.meshwhisper.app.ui.components

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.data.model.LastKnownLocationEntity
import com.meshwhisper.app.ui.theme.*
import com.meshwhisper.app.util.PlusCodeHelper
import java.util.Locale

/**
 * Pinned Rescue & Geolocation Card for DirectChatDetailScreen.
 * Displays hardware GPS fix age, 2x confidence radius, bearing compass, offline Plus Codes,
 * and quick copy/share actions for first responders.
 */
@Composable
fun RescueLocationCard(
    location: LastKnownLocationEntity,
    myLatitude: Double? = null,
    myLongitude: Double? = null,
    onOpenMap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // 1. Calculate fix age & status accent
    val fixAgeMs = (System.currentTimeMillis() - location.timestamp).coerceAtLeast(0L)
    val fixAgeMins = fixAgeMs / (60 * 1000L)
    val fixAgeHours = fixAgeMins / 60L

    val (ageText, statusColor) = when {
        fixAgeMins < 5 -> Pair("Last fix ${fixAgeMins}m ago", SaharaOnline)
        fixAgeMins < 60 -> Pair("Last fix ${fixAgeMins}m ago", SaharaWarning)
        fixAgeHours < 24 -> Pair("Stale fix: ${fixAgeHours}h ago", Color.Gray)
        else -> Pair("Stale fix: >24h ago", Color.Gray)
    }

    // 2. Calculate distance and bearing if local location is available
    var distanceText = "Distance calculating..."
    var bearingText = ""
    if (myLatitude != null && myLongitude != null) {
        val results = FloatArray(2)
        Location.distanceBetween(myLatitude, myLongitude, location.latitude, location.longitude, results)
        val distMeters = results[0]
        val bearingDeg = (results[1] + 360) % 360

        val distFormatted = if (distMeters < 1000) {
            "~${distMeters.toInt()}m"
        } else {
            String.format(Locale.US, "~%.1f km", distMeters / 1000.0)
        }
        distanceText = "$distFormatted away (user may have moved)"

        val cardinal = when (bearingDeg) {
            in 22.5..67.5 -> "NE"
            in 67.5..112.5 -> "E"
            in 112.5..157.5 -> "SE"
            in 157.5..202.5 -> "S"
            in 202.5..247.5 -> "SW"
            in 247.5..292.5 -> "W"
            in 292.5..337.5 -> "NW"
            else -> "N"
        }
        bearingText = "🧭 $cardinal (${bearingDeg.toInt()}°)"
    }

    // 3. 2x Confidence Accuracy Radius
    val accuracyText = if (location.accuracyMeters > 0f) {
        "±${(location.accuracyMeters * 2).toInt()}m search radius (Reported ±${location.accuracyMeters.toInt()}m)"
    } else {
        "Accuracy: Unknown"
    }

    // 4. Trigger & Battery Badge
    val triggerBadge = when (location.triggerType) {
        4 -> "🔋 Dying Gasp (${if (location.batteryPercent >= 0) "${location.batteryPercent}%" else "<5%"})"
        3 -> "⚠️ Low Battery (10%)"
        2 -> "⚠️ Battery Warning (15%)"
        5 -> "🆘 Manual SOS Beacon"
        else -> "📡 Periodic Breadcrumb"
    }

    // 5. Offline 10-char Plus Code & Decimal Coordinates
    val plusCode = PlusCodeHelper.encode(location.latitude, location.longitude)
    val rawCoords = String.format(Locale.US, "%.6f, %.6f", location.latitude, location.longitude)

    Card(
        colors = CardDefaults.cardColors(containerColor = SaharaSurfaceContainer),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SaharaOutlineVariant.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Status, Age & Battery Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = ageText,
                        color = SaharaOnSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Surface(
                    color = if (location.triggerType == 4 || location.triggerType == 5) SaharaErrorContainer else SaharaSurfaceContainerHigh,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = triggerBadge,
                        color = if (location.triggerType == 4 || location.triggerType == 5) SaharaOnErrorContainer else SaharaOnSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Distance & Bearing
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = distanceText,
                    color = SaharaOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                if (bearingText.isNotBlank()) {
                    Text(
                        text = bearingText,
                        color = SaharaPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            Text(
                text = accuracyText,
                color = SaharaOnSurfaceVariant,
                fontSize = 11.sp
            )

            if (!location.note.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = SaharaSurfaceContainerLow,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "📝 \"${location.note}\"",
                        color = SaharaOnSurface,
                        fontSize = 11.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = SaharaOutlineVariant.copy(alpha = 0.4f), thickness = 0.8.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // Offline Plus Code & GPS Coordinates Block
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tag,
                            contentDescription = null,
                            tint = SaharaPrimary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Plus Code: $plusCode",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = SaharaOnSurface
                        )
                    }
                    Text(
                        text = "GPS: $rawCoords",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = SaharaOnSurfaceVariant
                    )
                }

                // Action Buttons: Copy, Share, Open Map
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = {
                            val copyPayload = "Location: $rawCoords\nPlus Code: $plusCode\n(${location.alias} - $ageText)"
                            clipboardManager.setText(AnnotatedString(copyPayload))
                            Toast.makeText(context, "Coordinates & Plus Code copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Coordinates",
                            tint = SaharaPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Emergency Location: ${location.alias}")
                                putExtra(Intent.EXTRA_TEXT, "🚨 Emergency Location for ${location.alias}:\nGPS: $rawCoords\nOffline Plus Code: $plusCode\nSearch Radius: $accuracyText\nFix Time: $ageText")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Location with First Responders"))
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Location",
                            tint = SaharaPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    onOpenMap?.let { openMapAction ->
                        IconButton(
                            onClick = openMapAction,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = "Open Map",
                                tint = SaharaPrimary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

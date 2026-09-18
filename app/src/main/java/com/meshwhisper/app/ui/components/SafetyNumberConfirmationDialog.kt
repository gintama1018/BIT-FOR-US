package com.meshwhisper.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.ui.theme.*
import com.meshwhisper.core.identity.VerificationCandidate

@Composable
fun SafetyNumberConfirmationDialog(
    candidate: VerificationCandidate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isCollision = candidate.isCollisionResolution

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (isCollision) Icons.Default.Warning else Icons.Default.Shield,
                contentDescription = null,
                tint = if (isCollision) Color(0xFFC62828) else SaharaPrimary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = if (isCollision) "Resolve Node ID Collision" else "Confirm Safety Number",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isCollision) {
                    Text(
                        text = "Multiple cryptographic identities are claiming Node ID 0x${String.format("%016X", candidate.nodeId64).takeLast(6)}. Confirming this safety number will verify this scanned identity and permanently block the conflicting identity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC62828),
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = "Compare this 60-digit safety number with the number on the other device's screen. If the numbers match, confirm to authenticate end-to-end cryptographic trust.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SaharaOnSurfaceVariant
                    )
                }

                Surface(
                    color = SaharaSurfaceContainerLowest,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        text = candidate.safetyNumber,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = SaharaPrimary,
                        modifier = Modifier.padding(12.dp),
                        lineHeight = 20.sp
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = "Your Fingerprint: ${candidate.ourFingerprint.take(16)}...",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Peer Fingerprint: ${candidate.peerFingerprint.take(16)}...",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCollision) Color(0xFFC62828) else Color(0xFF2E7D32)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isCollision) "Resolve & Verify" else "Confirm & Verify")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(candidate.safetyNumber))
                    Toast.makeText(context, "Safety number copied", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

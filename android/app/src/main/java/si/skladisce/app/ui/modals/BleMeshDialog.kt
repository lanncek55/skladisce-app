package si.skladisce.app.ui.modals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import si.skladisce.app.ble.BleMeshState
import si.skladisce.app.ble.BleSyncStatus
import si.skladisce.app.data.SyncHeaderInfo
import si.skladisce.app.ui.theme.SkladisceCard
import si.skladisce.app.ui.theme.SkladisceColors

@Composable
fun BleMeshDialog(
    syncHeader: SyncHeaderInfo,
    bleStatus: BleSyncStatus,
    onDismiss: () -> Unit,
    onForceSync: () -> Unit,
    onManualSend: () -> Unit,
    onManualReceive: () -> Unit,
    onCancelManual: () -> Unit
) {
    val isManualBusy = bleStatus.state == BleMeshState.MANUAL_SENDING || bleStatus.state == BleMeshState.MANUAL_RECEIVING

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = SkladisceColors.BgCard,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, SkladisceColors.Border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Bluetooth, null, tint = SkladisceColors.Primary)
                        Text("BLE Mesh Diagnostika", color = SkladisceColors.TextMain, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Zapri", tint = SkladisceColors.TextMuted)
                    }
                }

                SkladisceCard(containerColor = SkladisceColors.BgPrimary) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Lokalna naprava: DEV-${syncHeader.deviceId}", color = SkladisceColors.TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Stanje baze (Hash): ${syncHeader.hashHex} (v${syncHeader.dbVersion})", color = SkladisceColors.Primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(bleStatus.statusMessage, color = SkladisceColors.TextMuted, fontSize = 12.sp)
                    }
                }

                Button(
                    onClick = onForceSync,
                    colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Prisili uskladitev (Broadcast)", color = Color(0xFF042F2E), fontWeight = FontWeight.Bold)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onManualSend, enabled = !isManualBusy, modifier = Modifier.weight(1f)) {
                        Text("Pošlji (Ročno)", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onManualReceive, enabled = !isManualBusy, modifier = Modifier.weight(1f)) {
                        Text("Prejmi (Ročno)", fontSize = 12.sp)
                    }
                }

                if (isManualBusy) {
                    Button(
                        onClick = onCancelManual,
                        colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.DangerBg, contentColor = SkladisceColors.Danger),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Prekliči ročni prenos") }
                }
            }
        }
    }
}

package si.skladisce.app.ui.modals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import si.skladisce.app.data.model.ShelfLocEntry
import si.skladisce.app.ui.AddEditModalRequest
import si.skladisce.app.ui.theme.SkladisceCard
import si.skladisce.app.ui.theme.SkladisceColors

private data class EditableShelfRow(
    var shelf: String,
    var countText: String,
    val isPrimary: Boolean
)

@Composable
fun AddEditItemDialog(
    request: AddEditModalRequest,
    warehouses: List<Pair<String, String>>,
    onGenerateSku: () -> String,
    onDismiss: () -> Unit,
    onSave: (
        isEdit: Boolean,
        sku: String,
        name: String,
        category: String,
        boxTotal: Int,
        minQuantity: Int,
        shelfCapacity: Int,
        shelfLocation: String,
        categoryType: String,
        primaryWarehouse: String,
        unit: String,
        notes: String,
        boxes: Map<Int, List<ShelfLocEntry>>
    ) -> Unit,
    onDelete: (String) -> Unit
) {
    val existing = request.existingItem
    val isEdit = existing != null

    var sku by remember { mutableStateOf(existing?.sku ?: request.prefillSku) }
    var name by remember { mutableStateOf(existing?.name ?: request.prefillName) }
    var category by remember { mutableStateOf(existing?.category ?: "Pohištvo") }
    var boxTotalText by remember { mutableStateOf((existing?.boxTotal ?: request.prefillBoxTotal).coerceAtLeast(1).toString()) }
    var minQtyText by remember { mutableStateOf((existing?.minQuantity ?: 1).toString()) }
    var shelfCapText by remember { mutableStateOf((existing?.shelfCapacity ?: 0).toString()) }
    var shelfLoc by remember { mutableStateOf(existing?.shelfLocation ?: "") }
    var categoryType by remember { mutableStateOf(existing?.categoryType ?: "furniture") }
    var primaryWarehouse by remember {
        mutableStateOf(
            existing?.primaryWarehouse?.ifBlank { null } ?: request.prefillWarehouse.ifBlank { "spodnje" }
        )
    }
    var unit by remember { mutableStateOf(existing?.unit ?: "kos") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }

    val boxTotal = (boxTotalText.toIntOrNull() ?: 1).coerceIn(1, 12)

    val boxRowsMap = remember(existing) {
        mutableStateMapOf<Int, MutableList<EditableShelfRow>>().apply {
            val initTotal = (existing?.boxTotal ?: request.prefillBoxTotal).coerceIn(1, 12)
            for (b in 1..initTotal) {
                val exBox = existing?.boxes?.get(b)
                val list = if (exBox != null && exBox.locations.isNotEmpty()) {
                    exBox.locations.filter { it.boxId == null }.mapIndexed { idx, l ->
                        EditableShelfRow(l.name, l.count.toString(), idx == 0)
                    }.toMutableList()
                } else {
                    mutableStateListOf(
                        EditableShelfRow(
                            shelf = request.prefillLocation.ifBlank { "Regal A-01" },
                            countText = if (existing != null) "0" else "1",
                            isPrimary = true
                        )
                    )
                }
                if (list.isEmpty()) {
                    list.add(EditableShelfRow("Regal A-01", "0", true))
                }
                put(b, list.toMutableList())
            }
        }
    }

    // Osigura da obstajajo vrstice za vse 1..boxTotal
    for (b in 1..boxTotal) {
        if (!boxRowsMap.containsKey(b)) {
            boxRowsMap[b] = mutableStateListOf(EditableShelfRow("Regal A-01", "0", true))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = SkladisceColors.BgCard,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, SkladisceColors.Border),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isEdit) "Uredi artikel" else "Dodaj nov artikel",
                        color = SkladisceColors.TextMain,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Zapri", tint = SkladisceColors.TextMuted)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = sku,
                        onValueChange = { if (!isEdit) sku = it },
                        readOnly = isEdit,
                        label = { Text("Številka artikla (SKU)*") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (!isEdit) {
                        OutlinedButton(onClick = { sku = onGenerateSku() }) {
                            Text("Generiraj", fontSize = 12.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Naziv artikla*") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Kategorija") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f)
                    )
                    OutlinedTextField(
                        value = boxTotalText,
                        onValueChange = { boxTotalText = it.filter(Char::isDigit) },
                        label = { Text("Št. škatel") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(0.7f)
                    )
                }

                Text("Privzeto skladišče:", color = SkladisceColors.TextMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    warehouses.take(3).forEach { (id, lbl) ->
                        FilterChip(
                            selected = primaryWarehouse == id,
                            onClick = { primaryWarehouse = id },
                            label = { Text(lbl.substringBefore(" "), fontSize = 11.5.sp) }
                        )
                    }
                }

                Text("Škatle in regali:", color = SkladisceColors.Primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                for (b in 1..boxTotal) {
                    val rows = boxRowsMap[b] ?: mutableListOf()
                    var refreshTick by remember { mutableStateOf(0) }
                    @Suppress("UNUSED_EXPRESSION") refreshTick

                    SkladisceCard(
                        borderColor = SkladisceColors.Border,
                        containerColor = SkladisceColors.BgPrimary
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Škatla $b/$boxTotal", color = SkladisceColors.Primary, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                TextButton(
                                    onClick = {
                                        rows.add(EditableShelfRow("", "0", false))
                                        refreshTick++
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text("+ Dodaj regal", fontSize = 11.5.sp)
                                }
                            }
                            rows.forEachIndexed { idx, row ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = row.shelf,
                                        onValueChange = {
                                            row.shelf = it
                                            refreshTick++
                                        },
                                        label = { Text(if (idx == 0) "Glavni regal" else "Rezerva") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1.5f)
                                    )
                                    OutlinedTextField(
                                        value = row.countText,
                                        onValueChange = {
                                            row.countText = it.filter(Char::isDigit)
                                            refreshTick++
                                        },
                                        label = { Text("Kos") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(0.7f)
                                    )
                                    if (idx > 0) {
                                        IconButton(onClick = {
                                            rows.removeAt(idx)
                                            refreshTick++
                                        }) {
                                            Icon(Icons.Default.Close, contentDescription = "Odstrani", tint = SkladisceColors.Danger)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minQtyText,
                        onValueChange = { minQtyText = it.filter(Char::isDigit) },
                        label = { Text("Min. zaloga") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Enota") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = shelfCapText,
                        onValueChange = { shelfCapText = it.filter(Char::isDigit) },
                        label = { Text("Kapaciteta police") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = shelfLoc,
                        onValueChange = { shelfLoc = it },
                        label = { Text("Polica TRG") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Opombe / Dimenzije") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isEdit) {
                        OutlinedButton(
                            onClick = { onDelete(sku) },
                            border = BorderStroke(1.dp, SkladisceColors.Danger),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SkladisceColors.Danger)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Izbriši")
                        }
                    }
                    Button(
                        onClick = {
                            val builtBoxes = mutableMapOf<Int, List<ShelfLocEntry>>()
                            for (b in 1..boxTotal) {
                                val rList = boxRowsMap[b].orEmpty()
                                builtBoxes[b] = rList.mapIndexed { idx, r ->
                                    val sh = r.shelf.trim().ifEmpty { "Brez lokacije" }
                                    ShelfLocEntry(
                                        name = sh,
                                        count = r.countText.toIntOrNull() ?: 0,
                                        isPrimary = idx == 0,
                                        warehouse = primaryWarehouse
                                    )
                                }
                            }
                            onSave(
                                isEdit,
                                sku,
                                name,
                                category,
                                boxTotal,
                                minQtyText.toIntOrNull() ?: 0,
                                shelfCapText.toIntOrNull() ?: 0,
                                shelfLoc,
                                categoryType,
                                primaryWarehouse,
                                unit,
                                notes,
                                builtBoxes
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Shrani artikel", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

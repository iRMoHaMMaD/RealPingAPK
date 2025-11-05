package com.realping.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.realping.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportBottomSheet(
    onDismiss: () -> Unit,
    onConf: () -> Unit,
    onEnc: () -> Unit,
    onCb: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = stringResource(R.string.import_title), style = MaterialTheme.typography.titleLarge)
            Text(text = stringResource(R.string.import_desc), style = MaterialTheme.typography.bodyMedium)
            Divider()
            Button(onClick = onConf, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.import_conf))
            }
            Button(onClick = onEnc, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.import_encrypted_file))
            }
            OutlinedButton(onClick = onCb, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.import_clipboard))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

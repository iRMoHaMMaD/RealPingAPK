package com.realping.app.ui.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StatTile(titleRes: Int, value: String) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(text = stringResource(titleRes), fontSize = 12.sp)
            Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

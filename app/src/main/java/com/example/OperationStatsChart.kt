package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.entry.entryModelOf

@Composable
fun OperationStatsChart(history: List<NfcHistoryEntity>) {
    val modeCounts = mapOf(
        "Read" to history.count { it.operationMode == NfcOperationMode.READ.name },
        "Write" to history.count { it.operationMode == NfcOperationMode.WRITE.name },
        "Reset" to history.count { it.operationMode == NfcOperationMode.RESET.name },
        "Profile" to history.count { it.operationMode == NfcOperationMode.WRITE_PROFILE.name }
    )

    val chartEntryModel = entryModelOf(
        modeCounts["Read"]?.toFloat() ?: 0f,
        modeCounts["Write"]?.toFloat() ?: 0f,
        modeCounts["Reset"]?.toFloat() ?: 0f,
        modeCounts["Profile"]?.toFloat() ?: 0f
    )

    val labels = listOf("Read", "Write", "Reset", "Profile")
    val horizontalAxisValueFormatter = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
        val index = value.toInt()
        if (index >= 0 && index < labels.size) labels[index] else ""
    }
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Operation Frequency", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Chart(
                chart = columnChart(),
                model = chartEntryModel,
                startAxis = rememberStartAxis(),
                bottomAxis = rememberBottomAxis(
                    valueFormatter = horizontalAxisValueFormatter
                ),
                modifier = Modifier.fillMaxWidth().height(150.dp)
            )
        }
    }
}

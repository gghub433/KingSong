package app.gyro.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyro.ui.theme.GyroColors
import app.gyro.ui.theme.TabularNumbers

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = GyroColors.Surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) {
                        Text(
                            title.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = GyroColors.TextDim,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

/** A labelled live value, e.g. "Напряжение 98,4 В". */
@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    icon: ImageVector? = null,
    valueColor: Color = GyroColors.Text,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = GyroColors.SurfaceHigh) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = GyroColors.TextDim, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = GyroColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = TabularNumbers.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
                    color = valueColor,
                    maxLines = 1,
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, style = MaterialTheme.typography.bodySmall, color = GyroColors.TextDim, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = GyroColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Two tiles per row. */
@Composable
fun TileRow(content: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        content(Modifier.weight(1f))
        second(Modifier.weight(1f))
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = GyroColors.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = GyroColors.TextDim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), color = valueColor)
    }
}

/** Tabs that are planned but not built yet list what is coming. */
@Composable
fun PlannedFeatures(
    title: String,
    subtitle: String,
    features: List<String>,
    header: (@Composable ColumnScope.() -> Unit)? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = GyroColors.TextDim)
            }
        }
        if (header != null) item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { header() } }
        item {
            SectionCard(title = "В разработке") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    features.forEach { f ->
                        Row {
                            Text("•", color = GyroColors.Accent, modifier = Modifier.width(18.dp))
                            Text(f, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun <T> SimpleList(items: List<T>, row: @Composable (T) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(items) { row(it) } }
}

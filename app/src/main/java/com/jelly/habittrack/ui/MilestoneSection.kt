package com.jelly.habittrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.jelly.habittrack.data.Milestone
import com.jelly.habittrack.data.MilestoneProgress

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MilestoneSection(progress: MilestoneProgress) {
    val next = progress.next
    val nextDescription = when (next) {
        null -> "已集齐全部徽章"
        Milestone.FIRST -> "完成第 1 次打卡后解锁首枚徽章"
        else -> "距${next.label}徽章还差 ${next.threshold - progress.current} 次"
    }
    SectionLabel("里程碑", nextDescription)
    if (next != null) {
        LinearProgressIndicator(
            progress = { progress.current.toFloat() / next.threshold },
            modifier = Modifier.fillMaxWidth()
        )
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (milestone in Milestone.entries) {
            val earned = progress.earned(milestone)
            val colors = MaterialTheme.colorScheme
            Surface(
                modifier = Modifier.semantics { stateDescription = if (earned) "已解锁" else "待解锁" },
                shape = RoundedCornerShape(12.dp),
                color = if (earned) colors.tertiaryContainer else colors.surfaceContainer,
                contentColor = if (earned) colors.onTertiaryContainer else colors.onSurfaceVariant
            ) {
                Text(
                    if (earned) "✓ ${milestone.label}" else milestone.label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

package com.saicmotor.media.ui.queue

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.saicmotor.media.R
import com.saicmotor.media.ui.common.Playback
import com.saicmotor.media.ui.common.PlayerState
import com.saicmotor.media.ui.common.QueueEntry
import com.saicmotor.media.ui.common.Row
import com.saicmotor.media.ui.common.RowsPageFragment
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What's playing and what's coming, in play order (shuffle included). */
class QueueFragment : RowsPageFragment() {

    private var scrolledToCurrent = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlayerState.queue.combine(PlayerState.transport) { q, t -> q to t.activeQueueId }
                    .collect { (queue, active) -> render(queue, active) }
            }
        }
    }

    override fun emptyText(): CharSequence = getString(R.string.queue_empty)

    /** Rows come from the player, not the server. */
    override suspend fun load(): List<Row> = rows(PlayerState.queue.value)

    private fun rows(queue: List<QueueEntry>): List<Row> =
        if (queue.isEmpty()) emptyList()
        else listOf(Row.Heading(getString(R.string.up_next))) + queue.map { Row.Queued(it) }

    private fun render(queue: List<QueueEntry>, active: Long) {
        adapter.activeQueueId = active
        val rows = rows(queue)
        adapter.submitList(rows) {
            if (!scrolledToCurrent && queue.isNotEmpty()) {
                scrolledToCurrent = true
                val index = queue.indexOfFirst { it.id == active }
                if (index > 0) (list.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(index, 0)
            }
        }
        if (rows.isEmpty()) state.showEmpty(getString(R.string.queue_empty)) else state.showContent()
    }

    override fun onQueued(entry: QueueEntry) = Playback.skipTo(entry.id)
}

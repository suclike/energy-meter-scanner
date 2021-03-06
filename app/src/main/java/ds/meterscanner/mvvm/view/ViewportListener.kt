package ds.meterscanner.mvvm.view

import lecho.lib.hellocharts.listener.ViewportChangeListener
import lecho.lib.hellocharts.model.Viewport
import lecho.lib.hellocharts.view.AbstractChartView

class ViewportListener(private vararg val syncedCharts: AbstractChartView) : ViewportChangeListener {
    override fun onViewportChanged(newViewport: Viewport) {
        for (chart in syncedCharts) {
            chart.currentViewport = Viewport(newViewport.left, chart.maximumViewport.top, newViewport.right, chart.maximumViewport.bottom)
        }
    }
}
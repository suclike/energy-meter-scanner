package ds.meterscanner.mvvm.viewmodel

import L
import android.content.ContentResolver
import android.net.Uri
import android.text.format.DateFormat
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.instance
import ds.bindingtools.binding
import ds.meterscanner.R
import ds.meterscanner.data.CsvCreator
import ds.meterscanner.db.model.Snapshot
import ds.meterscanner.mvvm.BindableViewModel
import ds.meterscanner.mvvm.viewmodel.StackMode.*
import ds.meterscanner.util.FileTools
import ds.meterscanner.util.MathTools
import ds.meterscanner.util.getColorTemp
import ds.meterscanner.util.profile
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.run
import lecho.lib.hellocharts.formatter.SimpleColumnChartValueFormatter
import lecho.lib.hellocharts.model.*
import lecho.lib.hellocharts.util.ChartUtils
import java.io.Serializable
import java.util.*

class ChartsViewModel(kodein: Kodein) : BindableViewModel(kodein) {

    var checkedButtonId: Int by binding()
    var columnsData: ColumnChartData by binding()
    var linesData: LineChartData by binding()

    var tempVisible = true
    var positiveCorrection = true
    private var currMode: StackMode = MONTH
    var period: Period = Period.LAST_SEASON
        set(value) {
            field = value
            update()
        }

    private val calendar: Calendar = instance()
    private var data: List<SnapshotData> = listOf()

    init {
        checkedButtonId = when (currMode) {
            StackMode.AS_IS -> R.id.all_button
            StackMode.DAY -> R.id.days_button
            StackMode.WEEK -> R.id.weeks_button
            StackMode.MONTH -> R.id.months_button
            StackMode.YEAR -> R.id.years_button
        }

        update()
    }

    fun onCheckedChanged(id: Int) {
        val oldMode = currMode
        currMode = when (id) {
            R.id.days_button -> DAY
            R.id.weeks_button -> WEEK
            R.id.months_button -> MONTH
            R.id.years_button -> YEAR
            else -> AS_IS
        }
        if (currMode != oldMode)
            update()
    }

    fun toggleCorection(enabled: Boolean) {
        positiveCorrection = enabled
        update()
    }

    fun toggleTemperature(enabled: Boolean) {
        tempVisible = enabled
        update()
    }

    fun onDirectoryChoosen(cr: ContentResolver, uri: Uri) = async {
        val csvData = CsvCreator().createCsvData(data)
        try {
            FileTools.saveFile(cr, uri, csvData)
            showSnackbarCommand(getString(R.string.file_saved))
        } catch (e: Exception) {
            e.printStackTrace()
            showSnackbarCommand(getString(R.string.io_error))
        }
    }

    private fun update() = async {
        L.i("chart: set mode $currMode")
        toggleProgress(true)

        val snapshots = db.getAllSnapshots(calculatePeriodStart(period))
        val snapshotData = run(CommonPool + lifecycleJob) {
            val snapshotData = profile("prepareSnapshotData") { prepareSnapshotData(snapshots) }
            if (currMode == AS_IS)
                snapshotData
            else
                profile("stackData") { stackData(snapshotData) }
        }
        data = snapshotData
        val (cols, lines) = run(CommonPool + lifecycleJob) { profile("prepareChartData") { prepareChartData(snapshotData) } }

        toggleProgress(false)

        if (cols.columns.isEmpty()) {
            showSnackbarCommand(getString(R.string.empty_data))
            return@async
        }

        linesData = lines
        columnsData = cols

        //view.showSnackbar("ready")
    }

    private fun prepareChartData(data: List<SnapshotData>): Pair<ColumnChartData, LineChartData> {
        val columns = mutableListOf<Column>()
        val lines = mutableListOf<Line>()
        val axisValues = mutableListOf<AxisValue>()

        val formatter = SimpleColumnChartValueFormatter(1)
        data.forEachIndexed { i, d ->
            val value = SubcolumnValue(d.delta.toFloat())
            value.color = resources.getColor(d.colorId)
            val column = Column(listOf(value))
            column.setHasLabelsOnlyForSelected(true)
            column.formatter = formatter
            columns += column

            val axisValue = AxisValue(i.toFloat())
            axisValue.setLabel(formatDate(d.timestamp))
            //axisValue.setLabel("1")
            axisValues += axisValue

            if (tempVisible) {
                val tempValue = d.temperature ?: 0
                val line = Line(listOf(PointValue(i.toFloat(), tempValue.toFloat())))
                if (d.temperature == null)
                    line.pointRadius = 0
                else {
                    line.pointColor = resources.getColor(getColorTemp(tempValue))
                    line.setHasLabels(true)
                    //line.setHasLabelsOnlyForSelected(true)
                }

                lines += line
            }
        }

        val columnData = ColumnChartData(columns)
        val lineData = LineChartData(lines)
        columnData.axisXBottom = Axis()
            .setName(getString(R.string.date))
            .setValues(axisValues)
            .setMaxLabelChars(axisValues[0].labelAsChars.size - 1)
            .setHasTiltedLabels(true)
        columnData.axisYLeft = Axis()
            .setHasLines(true)
            .setName(getString(R.string.consumption))
        lineData.axisYLeft = Axis
            .generateAxisFromRange(-50f, 50f, 5f)
            .setHasLines(true)
            .setName(getString(R.string.t_label))

        lineData.isValueLabelBackgroundEnabled = false
        lineData.setValueLabelsTextColor(ChartUtils.DEFAULT_DARKEN_COLOR)

        return columnData to lineData
    }

    private fun stackData(snapshotData: List<SnapshotData>): List<SnapshotData> {
        val firstStackStart: Long = truncDate(snapshotData.first().timestamp, currMode)
        val lastStackStart: Long = truncDate(snapshotData.last().timestamp, currMode)

        val periods = mutableListOf<Long>()
        var ongoingPeriod = firstStackStart
        while (ongoingPeriod <= lastStackStart) {
            periods += ongoingPeriod
            ongoingPeriod = getNextStackStart(ongoingPeriod)
        }

        val stackedData = mutableListOf<SnapshotData>()

        var cursor = 0
        for ((i, time) in periods.withIndex()) {
            //L.d("period $i: ${time.formatMillis()}")
            val stackedItem = SnapshotData(timestamp = time, colorId = R.color.colorAccent)
            val closingTime = getNextStackStart(time)
            val temps = mutableListOf<Int>()
            while (cursor < snapshotData.size && snapshotData[cursor].timestamp < closingTime) {
                val d = snapshotData[cursor]
                if (d.temperature != null)
                    temps += d.temperature!!
                //L.v("found data for this period: date=${formatTimeDate(d.timestamp)} valueCorrected=${d.getValueWithOffset()} delta=${d.delta}")
                cursor++
            }
            //L.v("temps=$temps")

            val (data, nextData) = if (cursor < snapshotData.size) {
                snapshotData[cursor - 1] to snapshotData[cursor]
            } else {
                stackedData[i - 1] to snapshotData[cursor - 1]
            }

            val closingValue = MathTools.findY(
                data.timestamp.toDouble(),
                data.getValueWithOffset(),
                nextData.timestamp.toDouble(),
                nextData.getValueWithOffset(),
                closingTime.toDouble()
            )
            stackedItem.value = closingValue
            stackedItem.temperature = if (!temps.isEmpty()) temps.average().toInt() else null
            if (i == 0)
                stackedItem.delta = closingValue - snapshotData[0].value
            else
                stackedItem.delta = closingValue - stackedData[i - 1].value

            if (stackedItem.delta < 0)
                stackedItem.colorId = R.color.disabled

            //L.v("estimated closing value=${stackedItem.value} delta=${stackedItem.delta} date=${formatTimeDate(closingTime)} temp=${stackedItem.temperature}")
            stackedData += stackedItem
        }
        return stackedData
    }

    private fun prepareSnapshotData(snapshots: List<Snapshot>): List<SnapshotData> {
        val snapshotData = mutableListOf<SnapshotData>()

        for ((i, snapshot) in snapshots.withIndex()) {
            var delta = 0.0
            val data = SnapshotData(snapshot.value, delta, 0.0, snapshot.timestamp, snapshot.outsideTemp, R.color.colorAccent)

            if (i > 0) {
                val prevData = snapshotData[i - 1]
                data.offset = prevData.offset
                delta = snapshot.value - prevData.value
                if (delta + prefs.correctionThreshold < 0) {
                    data.colorId = R.color.disabled
                    if (i < snapshots.size - 1 && positiveCorrection) {
                        val nextSnapshot = snapshots[i + 1]
                        delta = MathTools.findY(
                            prevData.timestamp.toDouble(),
                            prevData.delta,
                            nextSnapshot.timestamp.toDouble(),
                            nextSnapshot.value - snapshot.value,
                            snapshot.timestamp.toDouble()
                        )

                        data.offset += prevData.value - snapshot.value + delta
                    }
                }
                data.delta = delta
            }

            //L.v("#$i ${formatTimeDate(data.timestamp)} value=${data.value} valueCorrected=${data.getValueWithOffset()} delta=${data.delta}")
            snapshotData += data
        }
        return snapshotData
    }

    private fun calculatePeriodStart(period: Period): Long {
        calendar.time = Date()
        return when (period) {
            Period.YEAR -> {
                calendar.add(Calendar.YEAR, -1)
                calendar.timeInMillis
            }
            Period.LAST_SEASON -> {
                calendar.timeInMillis = truncDate(calendar.timeInMillis, MONTH)
                calendar.set(Calendar.MONTH, 8)
                if (calendar.after(Calendar.getInstance()))
                    calendar.add(Calendar.YEAR, -1)
                calendar.timeInMillis
            }
            else -> 0
        }
    }

    private fun truncDate(timestamp: Long, mode: StackMode): Long {
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        when (mode) {
            WEEK -> calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            MONTH -> calendar.set(Calendar.DAY_OF_MONTH, 1)
            YEAR -> calendar.set(Calendar.DAY_OF_YEAR, 1)
            else -> {
            }
        }

        return calendar.timeInMillis
    }

    private fun getNextStackStart(timestamp: Long): Long {
        calendar.timeInMillis = timestamp
        calendar.add(when (currMode) {
            DAY -> Calendar.DAY_OF_YEAR
            WEEK -> Calendar.WEEK_OF_YEAR
            MONTH -> Calendar.MONTH
            YEAR -> Calendar.YEAR
            else -> error("wrong stack type")
        }, 1)
        return calendar.timeInMillis
    }

    private fun formatDate(timestamp: Long): String = when (currMode) {
        StackMode.AS_IS -> DateFormat.format("dd-MM-yy", timestamp).toString()
        StackMode.DAY -> DateFormat.format("dd-MM-yy", timestamp).toString()
        StackMode.WEEK -> DateFormat.format("M/yy", timestamp).toString()
        StackMode.MONTH -> DateFormat.format("M/yy", timestamp).toString()
        StackMode.YEAR -> DateFormat.format("yyyy", timestamp).toString()
    }

}

enum class StackMode : Serializable { AS_IS, DAY, WEEK, MONTH, YEAR }

enum class Period : Serializable { ALL, YEAR, LAST_SEASON/*, THREE_MONTHS, MONTH, WEEK*/ }

data class SnapshotData(
    var value: Double = 0.0,
    var delta: Double = 0.0,
    var offset: Double = 0.0,
    var timestamp: Long = 0,
    var temperature: Int? = null,
    var colorId: Int = 0
) {
    fun getValueWithOffset() = value + offset
}
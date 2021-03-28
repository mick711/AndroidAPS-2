package info.nightscout.androidaps.plugins.treatments.fragments

import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.android.support.DaggerFragment
import info.nightscout.androidaps.R
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.database.entities.Bolus
import info.nightscout.androidaps.database.entities.BolusCalculatorResult
import info.nightscout.androidaps.database.entities.Carbs
import info.nightscout.androidaps.database.entities.UserEntry.*
import info.nightscout.androidaps.database.transactions.InvalidateBolusCalculatorResultTransaction
import info.nightscout.androidaps.database.transactions.InvalidateBolusTransaction
import info.nightscout.androidaps.database.transactions.InvalidateCarbsTransaction
import info.nightscout.androidaps.databinding.TreatmentsBolusCarbsFragmentBinding
import info.nightscout.androidaps.databinding.TreatmentsBolusCarbsItemBinding
import info.nightscout.androidaps.dialogs.WizardInfoDialog
import info.nightscout.androidaps.events.EventAutosensCalculationFinished
import info.nightscout.androidaps.events.EventTreatmentChange
import info.nightscout.androidaps.interfaces.ActivePluginProvider
import info.nightscout.androidaps.interfaces.ProfileFunction
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.logging.UserEntryLogger
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload
import info.nightscout.androidaps.plugins.general.nsclient.events.EventNSClientRestart
import info.nightscout.androidaps.plugins.treatments.events.EventTreatmentUpdateGui
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.T
import info.nightscout.androidaps.utils.alertDialogs.OKDialog
import info.nightscout.androidaps.utils.buildHelper.BuildHelper
import info.nightscout.androidaps.utils.extensions.iobCalc
import info.nightscout.androidaps.utils.extensions.toVisibility
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.rx.AapsSchedulers
import info.nightscout.androidaps.utils.sharedPreferences.SP
import io.reactivex.Completable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class TreatmentsBolusCarbsFragment : DaggerFragment() {

    @Inject lateinit var rxBus: RxBusWrapper
    @Inject lateinit var sp: SP
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var resourceHelper: ResourceHelper
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var buildHelper: BuildHelper
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var repository: AppRepository
    @Inject lateinit var activePlugin: ActivePluginProvider

    class MealLink(
        val bolus: Bolus? = null,
        val carbs: Carbs? = null,
        val bolusCalculatorResult: BolusCalculatorResult? = null
    )

    private val disposable = CompositeDisposable()

    private val millsToThePast = T.days(30).msecs()

    private var _binding: TreatmentsBolusCarbsFragmentBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        TreatmentsBolusCarbsFragmentBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerview.setHasFixedSize(true)
        binding.recyclerview.layoutManager = LinearLayoutManager(view.context)

        binding.refreshFromNightscout.setOnClickListener {
            activity?.let { activity ->
                OKDialog.showConfirmation(activity, resourceHelper.gs(R.string.refresheventsfromnightscout) + "?") {
                    uel.log(Action.TREATMENTS_NS_REFRESH)
                    disposable +=
                        Completable.fromAction {
                            repository.deleteAllBolusCalculatorResults()
                            repository.deleteAllBoluses()
                            repository.deleteAllCarbs()
                        }
                            .subscribeOn(aapsSchedulers.io)
                            .observeOn(aapsSchedulers.main)
                            .subscribeBy(
                                onError = { aapsLogger.error("Error removing entries", it) },
                                onComplete = { rxBus.send(EventTreatmentChange(null)) }
                            )
                    rxBus.send(EventNSClientRestart())
                }
            }
        }
        binding.deleteFutureTreatments.setOnClickListener {
            activity?.let { activity ->
                OKDialog.showConfirmation(activity, resourceHelper.gs(R.string.overview_treatment_label), resourceHelper.gs(R.string.deletefuturetreatments) + "?", Runnable {
                    uel.log(Action.DELETE_FUTURE_TREATMENTS)
                    repository
                        .getBolusesDataFromTime(dateUtil._now(), false)
                        .observeOn(aapsSchedulers.main)
                        .subscribe { list ->
                            list.forEach { bolus ->
                                disposable += repository.runTransactionForResult(InvalidateBolusTransaction(bolus.id))
                                    .subscribe(
                                        { result -> result.invalidated.forEach { aapsLogger.debug(LTag.DATABASE, "Invalidated bolus $it") } },
                                        { aapsLogger.error(LTag.DATABASE, "Error while invalidating bolus", it) }
                                    )
                            }
                        }
                    repository
                        .getCarbsDataFromTime(dateUtil._now(), false)
                        .observeOn(aapsSchedulers.main)
                        .subscribe { list ->
                            list.forEach { carb ->
                                disposable += repository.runTransactionForResult(InvalidateCarbsTransaction(carb.id))
                                    .subscribe(
                                        { result -> result.invalidated.forEach { aapsLogger.debug(LTag.DATABASE, "Invalidated carbs $it") } },
                                        { aapsLogger.error(LTag.DATABASE, "Error while invalidating carbs", it) }
                                    )
                            }
                        }
                    repository
                        .getBolusCalculatorResultsDataFromTime(dateUtil._now(), false)
                        .observeOn(aapsSchedulers.main)
                        .subscribe { list ->
                            list.forEach { bolusCalc ->
                                disposable += repository.runTransactionForResult(InvalidateBolusCalculatorResultTransaction(bolusCalc.id))
                                    .subscribe(
                                        { result -> result.invalidated.forEach { aapsLogger.debug(LTag.DATABASE, "Invalidated bolusCalculatorResult $it") } },
                                        { aapsLogger.error(LTag.DATABASE, "Error while invalidating bolusCalculatorResult", it) }
                                    )
                            }
                        }
                    binding.deleteFutureTreatments.visibility = View.GONE
                })
            }
        }
        val nsUploadOnly = sp.getBoolean(R.string.key_ns_upload_only, true) || !buildHelper.isEngineeringMode()
        if (nsUploadOnly) binding.refreshFromNightscout.visibility = View.GONE
        binding.showInvalidated.setOnCheckedChangeListener { _, _ ->
            rxBus.send(EventTreatmentUpdateGui())
        }
    }

    private fun bolusMealLinksWithInvalid(now: Long) = repository
        .getBolusesIncludingInvalidFromTime(now - millsToThePast, false)
        .map { bolus -> bolus.map { MealLink(bolus = it) } }

    private fun carbsMealLinksWithInvalid(now: Long) = repository
        .getCarbsIncludingInvalidFromTime(now - millsToThePast, false)
        .map { carb -> carb.map { MealLink(carbs = it) } }

    private fun calcResultMealLinksWithInvalid(now: Long) = repository
        .getBolusCalculatorResultsIncludingInvalidFromTime(now - millsToThePast, false)
        .map { calc -> calc.map { MealLink(bolusCalculatorResult = it) } }

    private fun bolusMealLinks(now: Long) = repository
        .getBolusesDataFromTime(now - millsToThePast, false)
        .map { bolus -> bolus.map { MealLink(bolus = it) } }

    private fun carbsMealLinks(now: Long) = repository
        .getCarbsDataFromTime(now - millsToThePast, false)
        .map { carb -> carb.map { MealLink(carbs = it) } }

    private fun calcResultMealLinks(now: Long) = repository
        .getBolusCalculatorResultsDataFromTime(now - millsToThePast, false)
        .map { calc -> calc.map { MealLink(bolusCalculatorResult = it) } }

    fun swapAdapter() {
        val now = System.currentTimeMillis()

        if (binding.showInvalidated.isChecked)
            disposable += carbsMealLinksWithInvalid(now)
                .zipWith(bolusMealLinksWithInvalid(now)) { first, second -> first + second }
                .zipWith(calcResultMealLinksWithInvalid(now)) { first, second -> first + second }
                .map { ml ->
                    ml.sortedBy {
                        it.carbs?.timestamp ?: it.bolus?.timestamp
                        ?: it.bolusCalculatorResult?.timestamp
                    }
                }
                .observeOn(aapsSchedulers.main)
                .subscribe { list ->
                    binding.recyclerview.swapAdapter(RecyclerViewAdapter(list), true)
                    binding.deleteFutureTreatments.visibility = list.isNotEmpty().toVisibility()
                }
        else
            disposable += carbsMealLinks(now)
                .zipWith(bolusMealLinks(now)) { first, second -> first + second }
                .zipWith(calcResultMealLinks(now)) { first, second -> first + second }
                .map { ml ->
                    ml.sortedBy {
                        it.carbs?.timestamp ?: it.bolus?.timestamp
                        ?: it.bolusCalculatorResult?.timestamp
                    }
                }
                .observeOn(aapsSchedulers.main)
                .subscribe { list ->
                    binding.recyclerview.swapAdapter(RecyclerViewAdapter(list), true)
                    binding.deleteFutureTreatments.visibility = list.isNotEmpty().toVisibility()
                }

    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        swapAdapter()
        disposable += rxBus
            .toObservable(EventTreatmentChange::class.java)
            .observeOn(aapsSchedulers.main)
            .debounce(1L, TimeUnit.SECONDS)
            .subscribe({ swapAdapter() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTreatmentUpdateGui::class.java) // TODO join with above
            .observeOn(aapsSchedulers.io)
            .debounce(1L, TimeUnit.SECONDS)
            .subscribe({ swapAdapter() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAutosensCalculationFinished::class.java)
            .observeOn(aapsSchedulers.main)
            .debounce(1L, TimeUnit.SECONDS)
            .subscribe({ swapAdapter() }, fabricPrivacy::logException)
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        disposable.clear()
    }

    @Synchronized
    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerview.adapter = null // avoid leaks
        _binding = null
    }

    inner class RecyclerViewAdapter internal constructor(var mealLinks: List<MealLink>) : RecyclerView.Adapter<RecyclerViewAdapter.MealLinkLoadedViewHolder>() {

        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): MealLinkLoadedViewHolder {
            val v = LayoutInflater.from(viewGroup.context).inflate(R.layout.treatments_bolus_carbs_item, viewGroup, false)
            return MealLinkLoadedViewHolder(v)
        }

        override fun onBindViewHolder(holder: MealLinkLoadedViewHolder, position: Int) {
            val profile = profileFunction.getProfile() ?: return
            val ml = mealLinks[position]

            // Metadata
            holder.binding.metadataLayout.visibility = (ml.bolusCalculatorResult != null && (ml.bolusCalculatorResult.isValid || binding.showInvalidated.isChecked)).toVisibility()
            ml.bolusCalculatorResult?.let { bolusCalculatorResult ->
                holder.binding.date.text = dateUtil.dateAndTimeString(bolusCalculatorResult.timestamp)
            }

            // Bolus
            holder.binding.bolusLayout.visibility = (ml.bolus != null && (ml.bolus.isValid || binding.showInvalidated.isChecked)).toVisibility()
            ml.bolus?.let { bolus ->
                holder.binding.bolusDate.text = dateUtil.timeString(bolus.timestamp)
                holder.binding.insulin.text = resourceHelper.gs(R.string.formatinsulinunits, bolus.amount)
                holder.binding.bolusNs.visibility = (NSUpload.isIdValid(bolus.interfaceIDs.nightscoutId)).toVisibility()
                holder.binding.bolusPump.visibility = (bolus.interfaceIDs.pumpId != null).toVisibility()
                holder.binding.bolusInvalid.visibility = bolus.isValid.not().toVisibility()
                val iob = bolus.iobCalc(activePlugin, System.currentTimeMillis(), profile.dia)
                holder.binding.iob.text = resourceHelper.gs(R.string.formatinsulinunits, iob.iobContrib)
                if (iob.iobContrib != 0.0) holder.binding.iob.setTextColor(resourceHelper.gc(R.color.colorActive)) else holder.binding.iob.setTextColor(holder.binding.carbs.currentTextColor)
                if (bolus.timestamp > dateUtil._now()) holder.binding.date.setTextColor(resourceHelper.gc(R.color.colorScheduled)) else holder.binding.date.setTextColor(holder.binding.carbs.currentTextColor)
                holder.binding.mealOrCorrection.text =
                    when (ml.bolus.type) {
                        Bolus.Type.SMB    -> "SMB"
                        Bolus.Type.NORMAL -> resourceHelper.gs(R.string.mealbolus)
                        else              -> ""
                    }
            }
            // Carbs
            holder.binding.carbsLayout.visibility = (ml.carbs != null && (ml.carbs.isValid || binding.showInvalidated.isChecked)).toVisibility()
            ml.carbs?.let { carbs ->
                holder.binding.carbsDate.text = dateUtil.timeString(carbs.timestamp)
                holder.binding.carbs.text = resourceHelper.gs(R.string.format_carbs, carbs.amount.toInt())
                holder.binding.carbsNs.visibility = (NSUpload.isIdValid(carbs.interfaceIDs.nightscoutId)).toVisibility()
                holder.binding.carbsPump.visibility = (carbs.interfaceIDs.pumpId != null).toVisibility()
                holder.binding.carbsInvalid.visibility = carbs.isValid.not().toVisibility()
            }

            holder.binding.bolusRemove.visibility = (ml.bolus?.isValid == true).toVisibility()
            holder.binding.carbsRemove.visibility = (ml.carbs?.isValid == true).toVisibility()
            holder.binding.bolusRemove.tag = ml
            holder.binding.carbsRemove.tag = ml
            holder.binding.calculation.tag = ml
        }

        override fun getItemCount(): Int {
            return mealLinks.size
        }

        inner class MealLinkLoadedViewHolder internal constructor(view: View) : RecyclerView.ViewHolder(view) {

            val binding = TreatmentsBolusCarbsItemBinding.bind(view)

            init {
                binding.calculation.setOnClickListener {
                    val mealLinkLoaded = it.tag as MealLink
                    mealLinkLoaded.bolusCalculatorResult?.let { bolusCalculatorResult ->
                        WizardInfoDialog().also { wizardDialog ->
                            wizardDialog.setData(bolusCalculatorResult)
                            wizardDialog.show(childFragmentManager, "WizardInfoDialog")
                        }
                    }
                }
                binding.calculation.paintFlags = binding.calculation.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                binding.bolusRemove.setOnClickListener { ml ->
                    val bolus = (ml.tag as MealLink?)?.bolus ?: return@setOnClickListener
                    activity?.let { activity ->
                        val text = resourceHelper.gs(R.string.configbuilder_insulin) + ": " +
                            resourceHelper.gs(R.string.formatinsulinunits, bolus.amount) + "\n" +
                            resourceHelper.gs(R.string.date) + ": " + dateUtil.dateAndTimeString(bolus.timestamp)
                        OKDialog.showConfirmation(activity, resourceHelper.gs(R.string.removerecord), text, Runnable {
                            uel.log(
                                Action.TREATMENT_REMOVED,
                                ValueWithUnit(bolus.timestamp, Units.Timestamp),
                                ValueWithUnit(bolus.amount, Units.U)
                                //              ValueWithUnit(mealLinkLoaded.carbs.toInt(), Units.G)
                            )
                            disposable += repository.runTransactionForResult(InvalidateBolusTransaction(bolus.id))
                                .subscribe(
                                    { result -> result.invalidated.forEach { aapsLogger.debug(LTag.DATABASE, "Invalidated bolus $it") } },
                                    { aapsLogger.error(LTag.DATABASE, "Error while invalidating bolus", it) }
                                )
                        })
                    }
                }
                binding.bolusRemove.paintFlags = binding.bolusRemove.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                binding.carbsRemove.setOnClickListener { ml ->
                    val carb = (ml.tag as MealLink?)?.carbs ?: return@setOnClickListener
                    activity?.let { activity ->
                        val text = resourceHelper.gs(R.string.carbs) + ": " +
                            resourceHelper.gs(R.string.carbs) + ": " + resourceHelper.gs(R.string.format_carbs, carb.amount.toInt()) + "\n" +
                            resourceHelper.gs(R.string.date) + ": " + dateUtil.dateAndTimeString(carb.timestamp)
                        OKDialog.showConfirmation(activity, resourceHelper.gs(R.string.removerecord), text, Runnable {
                            uel.log(
                                Action.TREATMENT_REMOVED,
                                ValueWithUnit(carb.timestamp, Units.Timestamp),
                                ValueWithUnit(carb.amount, Units.G)
                            )
                            disposable += repository.runTransactionForResult(InvalidateCarbsTransaction(carb.id))
                                .subscribe(
                                    { result -> result.invalidated.forEach { aapsLogger.debug(LTag.DATABASE, "Invalidated carbs $it") } },
                                    { aapsLogger.error(LTag.DATABASE, "Error while invalidating carbs", it) }
                                )
                        })
                    }
                }
                binding.carbsRemove.paintFlags = binding.carbsRemove.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            }
        }
    }
}
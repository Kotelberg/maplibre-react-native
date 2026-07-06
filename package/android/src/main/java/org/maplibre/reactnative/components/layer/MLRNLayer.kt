package org.maplibre.reactnative.components.layer

import android.content.Context
import com.facebook.common.logging.FLog
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import org.maplibre.android.location.LocationComponentConstants
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.ColorReliefLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.ModelLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.reactnative.components.AbstractMapFeature
import org.maplibre.reactnative.components.layer.style.MLRNStyle
import org.maplibre.reactnative.components.layer.style.MLRNStyleFactory
import org.maplibre.reactnative.components.mapview.MLRNMapView
import org.maplibre.reactnative.components.mapview.MLRNMapView.FoundLayerCallback
import org.maplibre.reactnative.utils.ExpressionParser.from

class MLRNLayer(
    context: Context?,
) : AbstractMapFeature(context) {
    private var mID: String? = null
    private var mSourceID: String? = null
    private var mAboveLayerID: String? = null
    private var mBelowLayerID: String? = null
    private var mLayerIndex: Int? = null
    private var mVisible: Boolean = false
    private var mMinZoomLevel: Double? = null
    private var mMaxZoomLevel: Double? = null
    private var mReactStyle: ReadableMap? = null
    private var mFilter: Expression? = null
    private var mHadFilter: Boolean = false
    private var mModelAssets: ReadableMap? = null
    private var mModelID: String? = null

    private var mLayerType: String? = null
    private var mSourceLayerID: String? = null

    private var mMap: MapLibreMap? = null
    private var mLayer: Layer? = null
    private var mMapView: MLRNMapView? = null

    var ID: String
        get() = mID!!
        set(id) {
            mID = id
        }

    fun setSourceID(sourceID: String?) {
        mSourceID = sourceID
    }

    fun setLayerType(layerType: String?) {
        mLayerType = layerType
    }

    fun setSourceLayerID(sourceLayerID: String?) {
        mSourceLayerID = sourceLayerID
        if (mLayer != null) {
            applySourceLayer()
        }
    }

    fun setAboveLayerID(aboveLayerID: String?) {
        if (mAboveLayerID != null && mAboveLayerID == aboveLayerID) {
            return
        }
        mAboveLayerID = aboveLayerID
        if (mLayer != null) {
            removeFromMap(mMapView!!)
            addAbove(mAboveLayerID!!)
        }
    }

    fun setBelowLayerID(belowLayerID: String?) {
        if (mBelowLayerID != null && mBelowLayerID == belowLayerID) {
            return
        }
        mBelowLayerID = belowLayerID
        if (mLayer != null) {
            removeFromMap(mMapView!!)
            addBelow(mBelowLayerID!!)
        }
    }

    fun setLayerIndex(layerIndex: Int) {
        if (mLayerIndex != null && mLayerIndex == layerIndex) {
            return
        }
        mLayerIndex = layerIndex
        if (mLayer != null) {
            removeFromMap(mMapView!!)
            addAtIndex(mLayerIndex!!)
        }
    }

    fun setVisible(visible: Boolean) {
        mVisible = visible
        if (mLayer != null) {
            val visibility = if (mVisible) Property.VISIBLE else Property.NONE
            mLayer!!.setProperties(PropertyFactory.visibility(visibility))
        }
    }

    fun setMinZoomLevel(minZoomLevel: Double) {
        mMinZoomLevel = minZoomLevel
        if (mLayer != null) {
            mLayer!!.setMinZoom(minZoomLevel.toFloat())
        }
    }

    fun setMaxZoomLevel(maxZoomLevel: Double) {
        mMaxZoomLevel = maxZoomLevel
        if (mLayer != null) {
            mLayer!!.setMaxZoom(maxZoomLevel.toFloat())
        }
    }

    fun setReactStyle(reactStyle: ReadableMap?) {
        mReactStyle = reactStyle
        if (mLayer != null) {
            addStyles()
        }
    }

    // `model` layer only (fork extension, aligned to the upstream `model`
    // layer's generated `ModelLayer` API): asset id -> local GLB path. The
    // layer is created with the assets known at mount; later prop updates
    // (viewport reveals placements referencing new models) must reach the
    // live native layer too, or those features fall back to placeholder
    // cubes forever. `ModelLayer.setModelAssets` takes a `Map<String,String>`
    // (the generated binding), not the two parallel `String[]` the fork's
    // hand-written layer used.
    fun setModelAssets(modelAssets: ReadableMap?) {
        mModelAssets = modelAssets
        val layer = mLayer
        if (layer is ModelLayer) {
            modelAssetsMap()?.let { layer.setModelAssets(it) }
        }
    }

    // `modelID` selects one asset (by id, a key of `modelAssets`) for every
    // feature on the layer; `null` (the default) falls back to reading each
    // feature's own `model-id` property, so heterogeneous models can share a
    // layer. The generated `ModelLayer` has no constructor sugar for this —
    // it is a `model-id` layout expression set via `PropertyFactory`, so an
    // update after mount must be re-applied explicitly here.
    fun setModelID(modelID: String?) {
        mModelID = modelID
        val layer = mLayer
        if (layer is ModelLayer) {
            layer.setProperties(PropertyFactory.modelId(modelIdExpression()))
        }
    }

    private fun modelAssetsMap(): Map<String, String>? {
        val assets = mModelAssets ?: return null
        val map = mutableMapOf<String, String>()
        val iterator = assets.keySetIterator()
        while (iterator.hasNextKey()) {
            val id = iterator.nextKey()
            assets.getString(id)?.let { path -> map[id] = path }
        }
        return if (map.isEmpty()) null else map
    }

    private fun modelIdExpression(): Expression = mModelID?.let { Expression.literal(it) } ?: Expression.get("model-id")

    // The fork's hand-written `ModelLayer` constructor auto-wired per-feature
    // `bearing`/`size`/`footprint` expressions (plus a hardcoded minZoom 15)
    // directly inside the native SDK layer. The generated `ModelLayer` has no
    // such constructor sugar — every consumer composes its own `model-*`
    // paint/layout expressions, exactly like every other layer. This is the
    // explicit, component-level replacement: it preserves the *same*
    // rendering convention (feature properties named `bearing`/`size`/
    // `footprint` drive placement) so existing call sites keep working, with
    // the composition now living here instead of inside the native layer.
    // (The old hardcoded `minZoom 15` is intentionally not replicated — it
    // was an app-specific rendering-cost default, not a binding concern;
    // callers that want it can pass the generic `minzoom` prop.)
    private fun applyModelPlacementExpressions(layer: ModelLayer) {
        layer.setProperties(
            PropertyFactory.modelRotation(Expression.get("bearing")),
            PropertyFactory.modelScale(Expression.get("size")),
            PropertyFactory.modelFootprint(Expression.get("footprint")),
        )
    }

    fun setFilter(readableFilterArray: ReadableArray?) {
        val filterExpression = from(readableFilterArray)
        mFilter = filterExpression
        if (mLayer != null) {
            if (mFilter != null) {
                mHadFilter = true
                updateFilter(mFilter)
            } else if (mHadFilter) {
                updateFilter(Expression.literal(true))
            }
        }
    }

    private fun makeLayer(): Layer? =
        when (mLayerType) {
            "background" -> {
                BackgroundLayer(mID)
            }

            "circle" -> {
                val layer = CircleLayer(mID, mSourceID)
                if (mSourceLayerID != null) layer.setSourceLayer(mSourceLayerID)
                layer
            }

            "color-relief" -> {
                val layer = ColorReliefLayer(mID, mSourceID)
                if (mSourceLayerID != null) layer.setSourceLayer(mSourceLayerID)
                layer
            }

            "fill" -> {
                val layer = FillLayer(mID, mSourceID)
                if (mSourceLayerID != null) layer.setSourceLayer(mSourceLayerID)
                layer
            }

            "fill-extrusion" -> {
                val layer = FillExtrusionLayer(mID, mSourceID)
                if (mSourceLayerID != null) layer.setSourceLayer(mSourceLayerID)
                layer
            }

            "heatmap" -> {
                val layer = HeatmapLayer(mID, mSourceID)
                if (mSourceLayerID != null) layer.setSourceLayer(mSourceLayerID)
                layer
            }

            "hillshade" -> {
                val layer = HillshadeLayer(mID, mSourceID)
                if (mSourceLayerID != null) layer.setSourceLayer(mSourceLayerID)
                layer
            }

            "line" -> {
                val layer = LineLayer(mID, mSourceID)
                if (mSourceLayerID != null) layer.setSourceLayer(mSourceLayerID)
                layer
            }

            "model" -> {
                val assets = modelAssetsMap()
                if (assets == null) {
                    FLog.e(LOG_TAG, "model layer $mID has no modelAssets entries")
                    null
                } else {
                    // The generated `ModelLayer` ctor is the standard
                    // `(layerId, sourceId)` — assets and placement are set as
                    // ordinary properties below, not passed positionally.
                    val layer = ModelLayer(mID, mSourceID)
                    layer.setModelAssets(assets)
                    layer.setProperties(PropertyFactory.modelId(modelIdExpression()))
                    applyModelPlacementExpressions(layer)
                    layer
                }
            }

            "raster" -> {
                RasterLayer(mID, mSourceID)
            }

            "symbol" -> {
                val layer = SymbolLayer(mID, mSourceID)
                if (mSourceLayerID != null) layer.setSourceLayer(mSourceLayerID)
                layer
            }

            else -> {
                FLog.e(LOG_TAG, "Unknown layer type: $mLayerType")
                null
            }
        }

    private fun addStyles() {
        val reactStyle = mReactStyle ?: return
        val map = mMap ?: return
        val style = MLRNStyle(context, reactStyle, map)
        when (mLayer) {
            is BackgroundLayer -> MLRNStyleFactory.setBackgroundLayerStyle(mLayer as BackgroundLayer, style)
            is CircleLayer -> MLRNStyleFactory.setCircleLayerStyle(mLayer as CircleLayer, style)
            is ColorReliefLayer -> MLRNStyleFactory.setColorReliefLayerStyle(mLayer as ColorReliefLayer, style)
            is FillLayer -> MLRNStyleFactory.setFillLayerStyle(mLayer as FillLayer, style)
            is FillExtrusionLayer -> MLRNStyleFactory.setFillExtrusionLayerStyle(mLayer as FillExtrusionLayer, style)
            is HeatmapLayer -> MLRNStyleFactory.setHeatmapLayerStyle(mLayer as HeatmapLayer, style)
            is HillshadeLayer -> MLRNStyleFactory.setHillshadeLayerStyle(mLayer as HillshadeLayer, style)
            is LineLayer -> MLRNStyleFactory.setLineLayerStyle(mLayer as LineLayer, style)
            is RasterLayer -> MLRNStyleFactory.setRasterLayerStyle(mLayer as RasterLayer, style)
            is SymbolLayer -> MLRNStyleFactory.setSymbolLayerStyle(mLayer as SymbolLayer, style)
        }
    }

    private fun updateFilter(expression: Expression?) {
        when (mLayer) {
            is CircleLayer -> (mLayer as CircleLayer).setFilter(expression!!)
            is ColorReliefLayer -> (mLayer as ColorReliefLayer).setFilter(expression!!)
            is FillLayer -> (mLayer as FillLayer).setFilter(expression!!)
            is FillExtrusionLayer -> (mLayer as FillExtrusionLayer).setFilter(expression!!)
            is HeatmapLayer -> (mLayer as HeatmapLayer).setFilter(expression!!)
            is LineLayer -> (mLayer as LineLayer).setFilter(expression!!)
            is SymbolLayer -> (mLayer as SymbolLayer).setFilter(expression!!)
        }
    }

    private fun applySourceLayer() {
        when (mLayer) {
            is CircleLayer -> (mLayer as CircleLayer).setSourceLayer(mSourceLayerID)
            is ColorReliefLayer -> (mLayer as ColorReliefLayer).setSourceLayer(mSourceLayerID)
            is FillLayer -> (mLayer as FillLayer).setSourceLayer(mSourceLayerID)
            is FillExtrusionLayer -> (mLayer as FillExtrusionLayer).setSourceLayer(mSourceLayerID)
            is HeatmapLayer -> (mLayer as HeatmapLayer).setSourceLayer(mSourceLayerID)
            is HillshadeLayer -> (mLayer as HillshadeLayer).setSourceLayer(mSourceLayerID)
            is LineLayer -> (mLayer as LineLayer).setSourceLayer(mSourceLayerID)
            is SymbolLayer -> (mLayer as SymbolLayer).setSourceLayer(mSourceLayerID)
        }
    }

    private fun add() {
        if (!hasInitialized()) return
        val style = this.style ?: return

        val userBackgroundID = LocationComponentConstants.BACKGROUND_LAYER
        val userLocationBackgroundLayer = style.getLayer(userBackgroundID)

        val layer = mLayer ?: return

        if (userLocationBackgroundLayer != null) {
            style.addLayerBelow(layer, userBackgroundID)
            mMapView!!.layerAdded(layer)
            return
        }

        style.addLayer(layer)
        mMapView!!.layerAdded(layer)
    }

    private fun addAbove(aboveLayerID: String) {
        mMapView!!.waitForLayer(
            aboveLayerID,
            object : FoundLayerCallback {
                override fun found(layer: Layer?) {
                    if (!hasInitialized()) return
                    val style = this@MLRNLayer.style ?: return
                    val l = mLayer ?: return
                    style.addLayerAbove(l, aboveLayerID)
                    mMapView!!.layerAdded(l)
                }
            },
        )
    }

    private fun addBelow(belowLayerID: String) {
        mMapView!!.waitForLayer(
            belowLayerID,
            object : FoundLayerCallback {
                override fun found(layer: Layer?) {
                    if (!hasInitialized()) return
                    val style = this@MLRNLayer.style ?: return
                    val l = mLayer ?: return
                    style.addLayerBelow(l, belowLayerID)
                    mMapView!!.layerAdded(l)
                }
            },
        )
    }

    private fun addAtIndex(index: Int) {
        var idx = index
        if (!hasInitialized()) return
        val style = this.style ?: return
        val l = mLayer ?: return
        val layerSize = style.getLayers().size
        if (idx >= layerSize) {
            FLog.e(LOG_TAG, "Layer index is greater than number of layers on map. Layer inserted at end of layer stack.")
            idx = layerSize - 1
        }
        style.addLayerAt(l, idx)
        mMapView!!.layerAdded(l)
    }

    private fun insertLayer() {
        val style = this.style ?: return
        val id = mID ?: return
        if (style.getLayer(id) != null) return

        if (mAboveLayerID != null) {
            addAbove(mAboveLayerID!!)
        } else if (mBelowLayerID != null) {
            addBelow(mBelowLayerID!!)
        } else if (mLayerIndex != null) {
            addAtIndex(mLayerIndex!!)
        } else {
            add()
        }

        setZoomBounds()
    }

    private fun setZoomBounds() {
        if (mMaxZoomLevel != null) {
            mLayer!!.setMaxZoom(mMaxZoomLevel!!.toFloat())
        }
        if (mMinZoomLevel != null) {
            mLayer!!.setMinZoom(mMinZoomLevel!!.toFloat())
        }
    }

    override fun addToMap(mapView: MLRNMapView) {
        mMap = mapView.mapLibreMap
        mMapView = mapView

        val style = this.style ?: return

        val existingLayer = style.getLayer(mID!!)
        if (existingLayer != null) {
            mLayer = existingLayer
        } else {
            mLayer = makeLayer()
            insertLayer()
        }

        addStyles()
        if (mFilter != null) {
            mHadFilter = true
            updateFilter(mFilter)
        }
    }

    override fun removeFromMap(mapView: MLRNMapView) {
        val layer = mLayer ?: return
        this.style?.removeLayer(layer)
    }

    private val style: Style?
        get() = mMap?.style

    private fun hasInitialized(): Boolean = mMap != null && mLayer != null

    companion object {
        const val LOG_TAG: String = "MLRNLayer"
    }
}

import {
  codegenNativeComponent,
  CodegenTypes,
  type HostComponent,
  type ViewProps,
} from "react-native";

import type { UnsafeMixed } from "../../types/codegen/UnsafeMixed";
import type { StyleValue } from "../../utils/StyleValue";

export interface NativeProps extends ViewProps {
  id: string;
  layerType?: CodegenTypes.WithDefault<
    | "background"
    | "circle"
    | "color-relief"
    | "fill"
    | "fill-extrusion"
    | "heatmap"
    | "hillshade"
    | "line"
    | "model"
    | "raster"
    | "symbol",
    "background"
  >;

  source?: string;
  sourceLayer?: string;

  // `model` layer only (fork extension, aligned to the upstream `model`
  // layer's generated native API): asset id -> local GLB path, and the asset
  // id rendered for features. The RN-facing shape is unchanged from the
  // fork's own `model` layer; only the native leaf calls that consume these
  // props changed (see `MLRNLayer.m`/`MLRNLayer.kt`) — `modelId` on the
  // native side is a data-driven `NSExpression`/`PropertyFactory` property,
  // not a plain string/ctor argument.
  modelAssets?: UnsafeMixed<Record<string, string>>;
  modelID?: string;

  afterId?: string;
  beforeId?: string;
  layerIndex?: CodegenTypes.Int32;

  minzoom?: CodegenTypes.Double;
  maxzoom?: CodegenTypes.Double;

  filter?: UnsafeMixed<unknown[]>;
  reactStyle?: UnsafeMixed<Record<string, StyleValue>>;
}

export default codegenNativeComponent<NativeProps>(
  "MLRNLayer",
) as HostComponent<NativeProps>;

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

  // `model` layer only (fork extension): asset id -> local GLB path, and the
  // asset id rendered for features.
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

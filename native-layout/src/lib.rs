use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jfloatArray, jstring};
use serde::Serialize;

#[derive(Clone, Debug, PartialEq)]
struct Region {
    class_id: i32,
    score: f32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_samreader_app_document_NativeLayoutDetector_filterRegions(
    env: JNIEnv,
    _class: JClass,
    regions: JFloatArray,
) -> jfloatArray {
    let empty = || {
        env.new_float_array(0)
            .map(|array| array.into_raw())
            .unwrap_or_default()
    };
    let mut values = vec![0.0; env.get_array_length(&regions).unwrap_or(0) as usize];
    if env
        .get_float_array_region(&regions, 0, &mut values)
        .is_err()
    {
        return empty();
    }
    let filtered = filter_regions(&values);
    match env.new_float_array(filtered.len() as i32) {
        Ok(array) if env.set_float_array_region(&array, 0, &filtered).is_ok() => array.into_raw(),
        _ => empty(),
    }
}

#[derive(Serialize)]
struct PdfTextExtraction {
    pages: Vec<PdfTextPage>,
    error: Option<String>,
}

#[derive(Serialize)]
struct PdfTextPage {
    width: f32,
    height: f32,
    lines: Vec<PdfTextCell>,
    words: Vec<PdfTextCell>,
}

#[derive(Serialize)]
struct PdfTextCell {
    text: String,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_samreader_app_document_NativeLayoutDetector_extractPdfText(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let result = env
        .get_string(&path)
        .map(|value| value.to_string_lossy().into_owned())
        .map_err(|error| error.to_string())
        .and_then(|path| std::fs::read(path).map_err(|error| error.to_string()))
        .map(|bytes| {
            let pages = docling_pdf::textparse::pdf_text_pages(&bytes)
                .into_iter()
                .map(|page| PdfTextPage {
                    width: page.width,
                    height: page.height,
                    lines: page.cells.into_iter().map(PdfTextCell::from).collect(),
                    words: page.word_cells.into_iter().map(PdfTextCell::from).collect(),
                })
                .collect();
            PdfTextExtraction { pages, error: None }
        })
        .unwrap_or_else(|error| PdfTextExtraction {
            pages: Vec::new(),
            error: Some(error),
        });
    let json = serde_json::to_string(&result).unwrap_or_else(|error| {
        format!(r#"{{"pages":[],"error":"serialization failed: {error}"}}"#)
    });
    env.new_string(json)
        .map(|value| value.into_raw())
        .unwrap_or_default()
}

impl From<docling_pdf::TextCell> for PdfTextCell {
    fn from(value: docling_pdf::TextCell) -> Self {
        Self {
            text: value.text,
            left: value.l,
            top: value.t,
            right: value.r,
            bottom: value.b,
        }
    }
}

fn filter_regions(values: &[f32]) -> Vec<f32> {
    if values.len() % 6 != 0 {
        return Vec::new();
    }
    let mut candidates: Vec<Region> = values
        .chunks_exact(6)
        .filter_map(|row| {
            let region = Region {
                class_id: row[0] as i32,
                score: row[1],
                left: row[2].clamp(0.0, 1.0),
                top: row[3].clamp(0.0, 1.0),
                right: row[4].clamp(0.0, 1.0),
                bottom: row[5].clamp(0.0, 1.0),
            };
            (region.class_id >= 0
                && region.class_id < 23
                && region.score.is_finite()
                && region.right > region.left
                && region.bottom > region.top)
                .then_some(region)
        })
        .collect();
    candidates.sort_by(|a, b| b.score.total_cmp(&a.score));

    let mut kept: Vec<Region> = Vec::new();
    for candidate in candidates {
        let duplicate = kept.iter().any(|existing| {
            let overlap = iou(&candidate, existing);
            (candidate.class_id == existing.class_id && overlap >= 0.55)
                || (semantic_group(candidate.class_id) == semantic_group(existing.class_id)
                    && semantic_group(candidate.class_id) != 0
                    && overlap >= 0.72)
        });
        if !duplicate {
            kept.push(candidate);
        }
    }
    kept.sort_by(|a, b| {
        a.top
            .total_cmp(&b.top)
            .then_with(|| a.left.total_cmp(&b.left))
    });
    kept.into_iter()
        .flat_map(|r| [r.class_id as f32, r.score, r.left, r.top, r.right, r.bottom])
        .collect()
}

fn semantic_group(class_id: i32) -> i32 {
    match class_id {
        1 | 18 | 20 | 21 => 1,
        6 | 9 | 17 => 2,
        _ => 0,
    }
}

fn iou(a: &Region, b: &Region) -> f32 {
    let width = (a.right.min(b.right) - a.left.max(b.left)).max(0.0);
    let height = (a.bottom.min(b.bottom) - a.top.max(b.top)).max(0.0);
    let intersection = width * height;
    let union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top)
        - intersection;
    if union <= 0.0 {
        0.0
    } else {
        intersection / union
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn removes_same_class_duplicates() {
        let values = [
            2.0, 0.95, 0.1, 0.1, 0.4, 0.4, 2.0, 0.80, 0.11, 0.11, 0.41, 0.41,
        ];
        assert_eq!(filter_regions(&values).len(), 6);
    }

    #[test]
    fn merges_image_and_chart_predictions_for_same_region() {
        let values = [
            18.0, 0.90, 0.1, 0.1, 0.4, 0.4, 1.0, 0.80, 0.1, 0.1, 0.4, 0.4,
        ];
        let result = filter_regions(&values);
        assert_eq!(result.len(), 6);
        assert_eq!(result[0], 18.0);
    }

    #[test]
    fn preserves_distinct_columns() {
        let values = [
            2.0, 0.95, 0.05, 0.1, 0.45, 0.8, 2.0, 0.94, 0.55, 0.1, 0.95, 0.8,
        ];
        assert_eq!(filter_regions(&values).len(), 12);
    }
}

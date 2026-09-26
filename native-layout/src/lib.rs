use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use serde::Serialize;

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

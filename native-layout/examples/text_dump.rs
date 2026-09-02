fn main() {
    let path = std::env::args().nth(1).expect("usage: text_dump <pdf> [needle]");
    let needle = std::env::args().nth(2);
    let bytes = std::fs::read(path).expect("read PDF");
    for (page_index, page) in docling_pdf::textparse::pdf_text_pages(&bytes).iter().enumerate() {
        println!(
            "page {} ({:.0}x{:.0}) lines={} words={}",
            page_index + 1,
            page.width,
            page.height,
            page.cells.len(),
            page.word_cells.len(),
        );
        for cell in &page.cells {
            if needle.as_ref().is_none_or(|value| cell.text.contains(value)) {
                println!(
                    "  [{:.1},{:.1},{:.1},{:.1}] {}",
                    cell.l, cell.t, cell.r, cell.b, cell.text,
                );
            }
        }
    }
}

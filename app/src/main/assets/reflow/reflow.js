"use strict";

/*
 * Reading-view page script. The host (Android) exposes `SamReaderHost`:
 *   initialState(): JSON {style, selected, anchor}
 *   onSentence(id), onWord(word, sentenceId), onFigure(crop), onTopSentence(id)
 * and drives the page through `window.SamReflow`.
 */
(function () {
  const host = window.SamReaderHost;
  const root = document.documentElement;
  const sentences = Array.from(document.querySelectorAll(".s"));
  const words = typeof Intl.Segmenter === "function" ? new Intl.Segmenter(undefined, { granularity: "word" }) : null;
  let tapTarget = "sentence";

  function applyStyle(style) {
    root.dataset.theme = style.theme;
    root.dataset.tone = style.tone;
    root.dataset.font = style.font;
    root.style.setProperty("--font-size", style.fontSize + "px");
    root.style.setProperty("--line-height", String(style.lineHeight));
    root.style.setProperty("--translation-scale", String(style.translationScale));
    root.classList.toggle("bilingual", style.bilingual);
    tapTarget = style.tap;
    if (tapTarget !== "word") clearWord();
  }

  function select(id) {
    document.querySelectorAll(".s.selected").forEach((element) => element.classList.remove("selected"));
    const element = id && document.getElementById("s-" + id);
    if (element) element.classList.add("selected");
  }

  function clearWord() {
    if (window.CSS && CSS.highlights) CSS.highlights.delete("word");
  }

  function scrollToSentence(id, smooth) {
    const element = document.getElementById("s-" + id);
    if (element) element.scrollIntoView({ block: "start", behavior: smooth ? "smooth" : "auto" });
  }

  function setTranslations(values) {
    Object.keys(values).forEach((id) => {
      const slot = document.querySelector("#s-" + CSS.escape(id) + " > .t");
      if (slot) slot.textContent = values[id];
    });
  }

  /** The word under a point in the original text, as a DOM range. */
  function wordAt(x, y) {
    const caret = document.caretRangeFromPoint(x, y);
    if (!caret || caret.startContainer.nodeType !== Node.TEXT_NODE) return null;
    const node = caret.startContainer;
    if (!node.parentElement.closest(".o") || node.parentElement.closest(".katex")) return null;
    const offset = caret.startOffset;
    const segments = words ? words.segment(node.data) : fallbackSegments(node.data);
    for (const segment of segments) {
      const end = segment.index + segment.segment.length;
      if (segment.isWordLike && segment.index <= offset && offset <= end) {
        const range = document.createRange();
        range.setStart(node, segment.index);
        range.setEnd(node, end);
        const box = range.getBoundingClientRect();
        if (x < box.left - 4 || x > box.right + 4 || y < box.top - 4 || y > box.bottom + 4) return null;
        return range;
      }
    }
    return null;
  }

  function fallbackSegments(text) {
    const result = [];
    const pattern = /[\p{L}\p{N}][\p{L}\p{N}'’-]*/gu;
    let match;
    while ((match = pattern.exec(text))) result.push({ index: match.index, segment: match[0], isWordLike: true });
    return result;
  }

  document.addEventListener("click", (event) => {
    const figure = event.target.closest("img.visual");
    if (figure) {
      host.onFigure(figure.dataset.crop);
      return;
    }
    const sentence = event.target.closest(".s");
    if (tapTarget === "word") {
      const range = wordAt(event.clientX, event.clientY);
      if (range && sentence) {
        if (window.Highlight && CSS.highlights) CSS.highlights.set("word", new Highlight(range));
        host.onWord(range.toString(), sentence.id.slice(2));
        return;
      }
    }
    if (sentence && tapTarget === "sentence") host.onSentence(sentence.id.slice(2));
    else host.onSentence("");
  });

  /** Reports the first sentence visible at the top of the viewport while scrolling. */
  let reportScheduled = false;
  let lastReported = null;
  function reportTopSentence() {
    reportScheduled = false;
    let low = 0;
    let high = sentences.length - 1;
    let found = null;
    while (low <= high) {
      const middle = (low + high) >> 1;
      if (sentences[middle].getBoundingClientRect().bottom > 0) {
        found = sentences[middle];
        high = middle - 1;
      } else {
        low = middle + 1;
      }
    }
    const id = found ? found.id.slice(2) : null;
    if (id && id !== lastReported) {
      lastReported = id;
      host.onTopSentence(id);
    }
  }
  window.addEventListener("scroll", () => {
    if (reportScheduled) return;
    reportScheduled = true;
    setTimeout(reportTopSentence, 250);
  }, { passive: true });

  document.querySelectorAll(".m[data-tex]").forEach((element) => {
    katex.render(element.dataset.tex, element, {
      displayMode: element.classList.contains("d"),
      throwOnError: false,
      output: "html",
    });
  });

  window.SamReflow = { applyStyle, select, clearWord, scrollToSentence, setTranslations };

  const state = JSON.parse(host.initialState());
  applyStyle(state.style);
  select(state.selected);
  if (state.anchor) scrollToSentence(state.anchor, false);
})();

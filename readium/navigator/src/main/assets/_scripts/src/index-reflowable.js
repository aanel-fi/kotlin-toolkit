//
//  Copyright 2021 Readium Foundation. All rights reserved.
//  Use of this source code is governed by the BSD-style license
//  available in the top-level LICENSE file of the project.
//

// Script used for reflowable resources.

import "./index";
import { getCssSelector } from "css-selector-generator";
import { rangeFromLocator, resolveLocatorY } from "./utils";

function resolveViewportAnchor(docY) {
  const viewportY = docY - window.scrollY;
  if (
    viewportY < 0 ||
    viewportY >= window.innerHeight ||
    typeof document.caretRangeFromPoint !== "function"
  ) {
    return null;
  }
  const caret = document.caretRangeFromPoint(window.innerWidth / 2, viewportY);
  const node = caret && caret.startContainer;
  if (!node || node.nodeType !== Node.TEXT_NODE || !node.parentElement) {
    return null;
  }
  const content = node.textContent;
  const offset = caret.startOffset;
  let start = Math.max(0, offset - 24);
  let end = Math.min(content.length, offset + 24);
  while (start < end && /\s/.test(content[start])) start++;
  while (end > start && /\s/.test(content[end - 1])) end--;
  if (start === end) return null;
  let cssSelector;
  try {
    cssSelector = getCssSelector(node.parentElement);
  } catch (_) {
    return null;
  }
  if (!cssSelector) return null;
  const locator = {
    locations: { cssSelector },
    text: {
      highlight: content.slice(start, end),
      before: content.slice(Math.max(0, start - 24), start),
      after: content.slice(end, Math.min(content.length, end + 24)),
    },
  };
  const range = rangeFromLocator(locator);
  if (!range) return null;
  const rect = range.getBoundingClientRect();
  return {
    locator,
    sampleY: docY,
    resolvedY: rect.top + window.scrollY + rect.height / 2,
  };
}

let resourceGeometryObserver = null;

function scheduleResourceGeometry(reason) {
  const state = resourceGeometryObserver;
  if (!state) return;
  if (state.reasons.indexOf(reason) === -1) state.reasons.push(reason);
  if (state.frame !== null) return;
  state.frame = requestAnimationFrame(() => {
    state.frame = null;
    if (resourceGeometryObserver !== state || !document.body) return;
    const reasons = state.reasons;
    state.reasons = [];
    state.callback({
      sequence: ++state.sequence,
      reasons,
      extentCssPx: Math.ceil(
        document.body.getBoundingClientRect().bottom + window.scrollY
      ),
    });
  });
}

function stopResourceGeometryObserver() {
  const state = resourceGeometryObserver;
  if (!state) return;
  resourceGeometryObserver = null;
  if (state.frame !== null) cancelAnimationFrame(state.frame);
  state.resizeObserver.disconnect();
  window.removeEventListener("resize", state.onViewportResize);
  document.removeEventListener("load", state.onResourceLoad, true);
  document.removeEventListener("error", state.onResourceLoad, true);
  if (document.fonts && document.fonts.removeEventListener) {
    document.fonts.removeEventListener("loadingdone", state.onFontsLoaded);
  }
}

function observeResourceGeometry(callback) {
  stopResourceGeometryObserver();
  const state = {
    callback,
    frame: null,
    reasons: [],
    sequence: 0,
    resizeObserver: null,
    onViewportResize: null,
    onResourceLoad: null,
    onFontsLoaded: null,
  };
  resourceGeometryObserver = state;
  const notify = (reason) => {
    if (resourceGeometryObserver === state) scheduleResourceGeometry(reason);
  };
  state.onViewportResize = () => notify("viewport");
  state.onResourceLoad = (event) => {
    const tagName = event.target && event.target.tagName;
    if (tagName === "IMG" || tagName === "LINK") notify("resource-load");
  };
  state.onFontsLoaded = () => notify("fonts");
  state.resizeObserver = new ResizeObserver(() => notify("resize"));
  state.resizeObserver.observe(document.body);
  state.resizeObserver.observe(document.documentElement);
  window.addEventListener("resize", state.onViewportResize);
  document.addEventListener("load", state.onResourceLoad, true);
  document.addEventListener("error", state.onResourceLoad, true);
  if (document.fonts) {
    if (document.fonts.addEventListener) {
      document.fonts.addEventListener("loadingdone", state.onFontsLoaded);
    }
    document.fonts.ready.then(state.onFontsLoaded);
  }
  notify("initial");
  return () => {
    if (resourceGeometryObserver === state) stopResourceGeometryObserver();
  };
}

for (const name of ["setCSSProperties", "setProperty", "removeProperty"]) {
  const original = window.readium[name];
  window.readium[name] = function (...args) {
    const result = original(...args);
    scheduleResourceGeometry("css");
    return result;
  };
}

window.readium.isReflowable = true;
window.readium.resolveLocatorY = resolveLocatorY;
window.readium.resolveViewportAnchor = resolveViewportAnchor;
window.readium.observeResourceGeometry = observeResourceGeometry;
window.readium.stopResourceGeometryObserver = stopResourceGeometryObserver;

document.addEventListener("DOMContentLoaded", function () {
  // Setups the `viewport` meta tag to disable zooming.
  let meta = document.createElement("meta");
  meta.setAttribute("name", "viewport");
  meta.setAttribute(
    "content",
    "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, shrink-to-fit=no"
  );
  document.head.appendChild(meta);
});

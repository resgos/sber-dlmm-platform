/**
 * QW-1 (Batch #4, Sprint 14) — CSV export helper.
 *
 * <p>Trivially small library replacement (we don't need PapaParse for
 * 3 callers × ≤ 12 columns each). Browser-side only — no backend
 * round-trip; the data we export is already loaded into the table.
 *
 * <h2>Why a 30-line helper not a npm dep?</h2>
 * <p>Two reasons:
 *   1. Bundle size budget — every dep adds 1-3 kB minified. Three pages
 *      × full PapaParse = ~25 kB for zero feature gain.
 *   2. CSV is dead-simple. Quote anything that contains comma, quote, or
 *      newline; double internal quotes; UTF-8 BOM так Excel-RU не ломает
 *      кириллицу при открытии. That's the entire spec we need.
 *
 * <p>If a caller needs streaming / 100k+ rows / true RFC 4180 — switch
 * to a server endpoint and let the backend stream it. This helper is
 * for "user clicks 'Download CSV' on a table they're already looking at".
 */

/**
 * Convert any cell value to a CSV-safe string. Handles:
 *   - null / undefined → ''
 *   - Date → ISO 8601
 *   - object → JSON.stringify (caller's responsibility to flatten if
 *     they want readable output)
 *   - everything else → String()
 */
function escape(value: unknown): string {
  if (value === null || value === undefined) return ''
  let str: string
  if (value instanceof Date) {
    str = value.toISOString()
  } else if (typeof value === 'object') {
    str = JSON.stringify(value)
  } else {
    str = String(value)
  }
  // Quote if contains comma, quote, newline, or carriage return.
  if (/[",\n\r]/.test(str)) {
    return '"' + str.replace(/"/g, '""') + '"'
  }
  return str
}

export interface CsvColumn<T> {
  /** Column header text shown in CSV first row. */
  header: string
  /** Accessor returning the raw value for one row. Helper escapes it. */
  accessor: (row: T) => unknown
}

/**
 * Build a CSV string from rows + column spec.
 * UTF-8 BOM prepended так Excel correctly recognises кириллицу.
 */
export function buildCsv<T>(rows: readonly T[], columns: readonly CsvColumn<T>[]): string {
  const headerLine = columns.map((c) => escape(c.header)).join(',')
  const dataLines = rows.map((row) =>
    columns.map((c) => escape(c.accessor(row))).join(','),
  )
  // BOM + CRLF line endings (Windows + Excel friendly).
  return '﻿' + [headerLine, ...dataLines].join('\r\n')
}

/**
 * Trigger a browser download of the given CSV string with the given
 * filename. Uses Blob URL + a synthetic anchor click. URL is revoked
 * on the next microtask to avoid leaks.
 */
export function downloadCsv(filename: string, csv: string): void {
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

/**
 * Convenience one-liner — build + download in a single call. Most
 * callers want this.
 */
export function exportToCsv<T>(
  filename: string,
  rows: readonly T[],
  columns: readonly CsvColumn<T>[],
): void {
  downloadCsv(filename, buildCsv(rows, columns))
}

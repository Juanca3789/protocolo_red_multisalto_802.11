import { useCallback, useEffect, useRef, useState } from 'react'
import Slide from './components/Slide'
import { exportSlidesToPdf } from './utils/exportPdf'
import { slides } from './slides/content'
import './styles/deck.css'

function App() {
  const [current, setCurrent] = useState(0)
  const [exporting, setExporting] = useState(false)
  const exportRefs = useRef([])

  const total = slides.length
  const go = useCallback(
    (delta) => setCurrent((c) => Math.max(0, Math.min(total - 1, c + delta))),
    [total],
  )

  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'ArrowRight' || e.key === ' ') {
        e.preventDefault()
        go(1)
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault()
        go(-1)
      } else if (e.key === 'Home') {
        setCurrent(0)
      } else if (e.key === 'End') {
        setCurrent(total - 1)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [go, total])

  const handleExport = async () => {
    setExporting(true)
    try {
      await exportSlidesToPdf(exportRefs.current.filter(Boolean))
    } finally {
      setExporting(false)
    }
  }

  return (
    <div className="app-shell">
      <div className="viewport">
        <div
          className="deck-track"
          style={{ transform: `translateX(-${current * 100}%)` }}
        >
          {slides.map((slide, i) => (
            <div key={slide.id} className="deck-slide-wrap">
              <Slide slide={slide} index={i} total={total} />
            </div>
          ))}
        </div>
      </div>

      <nav className="deck-nav" aria-label="Navegación de diapositivas">
        <button type="button" className="nav-arrow" onClick={() => go(-1)} disabled={current === 0} aria-label="Anterior">
          ←
        </button>
        <div className="nav-dots">
          {slides.map((s, i) => (
            <button
              key={s.id}
              type="button"
              className={`nav-dot ${i === current ? 'active' : ''}`}
              onClick={() => setCurrent(i)}
              aria-label={`Ir a diapositiva ${i + 1}`}
              aria-current={i === current ? 'true' : undefined}
            />
          ))}
        </div>
        <button
          type="button"
          className="nav-arrow"
          onClick={() => go(1)}
          disabled={current === total - 1}
          aria-label="Siguiente"
        >
          →
        </button>
        <button
          type="button"
          className="export-btn"
          onClick={handleExport}
          disabled={exporting}
        >
          {exporting ? 'Exportando…' : 'Exportar PDF'}
        </button>
      </nav>

      <div className="export-capture-root" aria-hidden="true">
        {slides.map((slide, i) => (
          <Slide
            key={`export-${slide.id}`}
            ref={(el) => {
              exportRefs.current[i] = el
            }}
            slide={slide}
            index={i}
            total={total}
          />
        ))}
      </div>
    </div>
  )
}

export default App

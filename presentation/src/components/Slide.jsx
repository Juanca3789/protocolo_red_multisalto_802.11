import { forwardRef } from 'react'

const Slide = forwardRef(function Slide({ slide, index, total }, ref) {
  return (
    <section
      ref={ref}
      className="slide"
      data-slide-id={slide.id}
      data-slide-index={index}
      aria-label={`Diapositiva ${index + 1}: ${slide.title}`}
    >
      <div className="slide-grid-bg" aria-hidden />
      <header className="slide-header">
        <div className="slide-section">{slide.section}</div>
        <div className="slide-counter">
          {String(index + 1).padStart(2, '0')} / {String(total).padStart(2, '0')}
        </div>
      </header>
      <div className="slide-headings">
        <h1 className="slide-title">{slide.title}</h1>
        {slide.subtitle && <p className="slide-subtitle">{slide.subtitle}</p>}
      </div>
      <div className="slide-content">{slide.content}</div>
      <footer className="slide-footer">
        <span className="brand">PCT</span>
        <span className="brand-sub">Protocolo de Control Topológico</span>
      </footer>
    </section>
  )
})

export default Slide

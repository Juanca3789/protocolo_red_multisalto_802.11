import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'
import { SLIDE_HEIGHT, SLIDE_WIDTH } from '../slides/content'

export async function exportSlidesToPdf(slideElements, filename = 'pct-presentacion.pdf') {
  if (!slideElements?.length) return

  const pdf = new jsPDF({
    orientation: 'landscape',
    unit: 'px',
    format: [SLIDE_WIDTH, SLIDE_HEIGHT],
    compress: true,
  })

  for (let i = 0; i < slideElements.length; i++) {
    const el = slideElements[i]
    if (!el) continue

    const canvas = await html2canvas(el, {
      scale: 2,
      useCORS: true,
      backgroundColor: '#070b12',
      logging: false,
      width: SLIDE_WIDTH,
      height: SLIDE_HEIGHT,
      windowWidth: SLIDE_WIDTH,
      windowHeight: SLIDE_HEIGHT,
    })

    const imgData = canvas.toDataURL('image/jpeg', 0.92)
    if (i > 0) pdf.addPage([SLIDE_WIDTH, SLIDE_HEIGHT], 'landscape')
    pdf.addImage(imgData, 'JPEG', 0, 0, SLIDE_WIDTH, SLIDE_HEIGHT)
  }

  pdf.save(filename)
}

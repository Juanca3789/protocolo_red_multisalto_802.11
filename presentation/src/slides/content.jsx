export const SLIDE_WIDTH = 1920
export const SLIDE_HEIGHT = 1080

export const slides = [
  {
    id: 'title',
    section: 'Intro',
    title: 'PCT — Protocolo de Control Topológico',
    subtitle: 'Red multisalto sobre Wi‑Fi convencional · Android 12+',
    content: (
      <div className="slide-title-body">
        <div className="hero-badge">Proyecto de grado</div>
        <p className="hero-lead">
          Protocolo distribuido de control y reconfiguración topológica con continuidad lógica
          para redes multisalto sin privilegios de administrador en el dispositivo ni modificación
          del driver Wi‑Fi.
        </p>
        <div className="hero-meta">
          <span>Biblioteca Android reutilizable</span>
          <span>Wi‑Fi Direct GO + STA legacy</span>
          <span>Control y datos en canales TCP separados</span>
        </div>
        <div className="hero-authors">
          Juan Carlos Clavijo Triviño · Brandon Stiven Ganzo Murcia
        </div>
      </div>
    ),
  },
  {
    id: 'architecture',
    section: '01',
    title: 'Arquitectura',
    subtitle: 'Vista de componentes y planos del protocolo',
    content: (
      <div className="two-col">
        <div>
          <h3 className="block-title">Roles en la aplicación</h3>
          <ul className="feature-list">
            <li><strong>Orquestador del nodo</strong> — ciclo de vida, fases y coordinación global</li>
            <li><strong>Gestor de uniones</strong> — negociación simétrica entre nodos (en diseño)</li>
            <li><strong>Plano de enlace</strong> — vecindad padre↔hijo y canales TCP vecinos</li>
            <li><strong>Plano de red</strong> — tablas de rutas y reenvío multisalto</li>
            <li><strong>Gestor de topología</strong> — versión del árbol, padre lógico y eventos</li>
          </ul>
          <h3 className="block-title mt">Conectividad Wi‑Fi</h3>
          <ul className="feature-list compact">
            <li><strong>Anfitrión P2P</strong> — grupo propio, SSID/PSK, lista de asociados</li>
            <li><strong>Cliente legacy</strong> — asociación Wi‑Fi al punto del padre</li>
            <li><strong>Descubrimiento de servicios</strong> — anuncio y búsqueda de nodos PCT</li>
            <li><strong>Transporte confiable</strong> — tramas binarias en dos puertos TCP</li>
          </ul>
        </div>
        <div className="diagram-box">
          <div className="arch-stack">
            <div className="arch-layer app">Aplicación · Chat / interfaz</div>
            <div className="arch-arrow">▼</div>
            <div className="arch-layer orch">Orquestación · Unión · Rutas · Topología</div>
            <div className="arch-arrow">▼</div>
            <div className="arch-layer wifi">GO P2P · STA legacy · DNS-SD · TCP</div>
            <div className="arch-arrow">▼</div>
            <div className="arch-layer radio">802.11 · Wi‑Fi Direct + infra</div>
          </div>
          <p className="diagram-caption">
            Regla: un solo actor actualiza las tablas de rutas para evitar condiciones de carrera.
          </p>
        </div>
      </div>
    ),
  },
  {
    id: 'connection-mode',
    section: '02',
    title: 'Modo de conexión',
    subtitle: 'Todo nodo es anfitrión GO; el árbol se forma solo por Wi‑Fi legacy',
    content: (
      <div className="two-col">
        <div>
          <div className="principle-card">
            <span className="principle-icon">⛔</span>
            <div>
              <strong>Prohibido</strong> usar el enlace P2P cliente↔cliente para armar la topología.
              El árbol no se cuelga de invitaciones Wi‑Fi Direct directas.
            </div>
          </div>
          <h3 className="block-title">Dos interfaces en un nodo puente</h3>
          <table className="spec-table">
            <thead>
              <tr><th>Dirección</th><th>Interfaz</th><th>Qué conoce</th></tr>
            </thead>
            <tbody>
              <tr>
                <td><span className="tag upstream">Hacia el padre</span></td>
                <td>Red Wi‑Fi legacy (STA)</td>
                <td>Dirección del gateway del padre</td>
              </tr>
              <tr>
                <td><span className="tag downstream">Hacia los hijos</span></td>
                <td>GO P2P propio</td>
                <td>IPs de dispositivos asociados abajo</td>
              </tr>
            </tbody>
          </table>
          <h3 className="block-title mt">Arranque típico</h3>
          <ol className="steps-list">
            <li>Escaneo de servicios → credenciales del padre + identidad lógica</li>
            <li>Asociación legacy al SoftAP del padre</li>
            <li>Activación del GO propio (cada nodo mantiene su subárbol)</li>
            <li>Enlace TCP al padre · saludo → confirmación → datos</li>
          </ol>
          <p className="note mt">
            No existe un nodo “especial”: el <em>primer encendido</em> inicia su subárbol;
            los demás se unen y a su vez son anfitriones de los suyos.
          </p>
        </div>
        <div className="diagram-box">
          <svg viewBox="0 0 420 320" className="network-svg" aria-hidden>
            <defs>
              <linearGradient id="nodeGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stopColor="#00d4aa" />
                <stop offset="100%" stopColor="#0088ff" />
              </linearGradient>
            </defs>
            <rect x="150" y="20" width="120" height="56" rx="8" fill="url(#nodeGrad)" opacity="0.9" />
            <text x="210" y="46" textAnchor="middle" fill="#0a0e17" fontSize="12" fontWeight="600">Primer nodo</text>
            <text x="210" y="62" textAnchor="middle" fill="#0a0e17" fontSize="10">GO propio</text>
            <rect x="30" y="200" width="120" height="56" rx="8" fill="#1a2332" stroke="#00d4aa" strokeWidth="2" />
            <text x="90" y="226" textAnchor="middle" fill="#e8f4ff" fontSize="12">Nodo puente</text>
            <text x="90" y="242" textAnchor="middle" fill="#5a7089" fontSize="9">GO + padre</text>
            <rect x="270" y="200" width="120" height="56" rx="8" fill="#1a2332" stroke="#0088ff" strokeWidth="2" />
            <text x="330" y="226" textAnchor="middle" fill="#e8f4ff" fontSize="12">Nodo hoja</text>
            <text x="330" y="242" textAnchor="middle" fill="#5a7089" fontSize="9">GO propio</text>
            <line x1="210" y1="76" x2="90" y2="200" stroke="#00d4aa" strokeWidth="2" strokeDasharray="6 4" />
            <line x1="210" y1="76" x2="330" y2="200" stroke="#0088ff" strokeWidth="2" strokeDasharray="6 4" />
            <text x="130" y="140" fill="#7eb8ff" fontSize="11">Wi‑Fi legacy</text>
            <text x="290" y="140" fill="#7eb8ff" fontSize="11">Wi‑Fi legacy</text>
            <text x="90" y="280" textAnchor="middle" fill="#5a7089" fontSize="10">Subárbol propio</text>
            <text x="330" y="280" textAnchor="middle" fill="#5a7089" fontSize="10">Subárbol propio</text>
          </svg>
          <p className="diagram-caption">
            Enlace padre→hijo = asociación legacy al SSID del GO padre. Identidad lógica = UUID, no MAC.
          </p>
        </div>
      </div>
    ),
  },
  {
    id: 'osi',
    section: '03',
    title: 'OSI emulado',
    subtitle: 'Mapa conceptual — PCT no implementa pila OSI completa',
    content: (
      <div className="osi-grid">
        <div className="osi-layer l1">
          <div className="osi-num">L1</div>
          <div className="osi-body">
            <strong>Física / Radio</strong>
            <p>Formación de grupo P2P, asociación legacy y descubrimiento de vecinos</p>
            <span className="osi-transport">802.11 · Wi‑Fi Direct + infra</span>
          </div>
          <span className="osi-status done">Operativo</span>
        </div>
        <div className="osi-layer l2">
          <div className="osi-num">L2</div>
          <div className="osi-body">
            <strong>Enlace · Vecinos directos</strong>
            <p>Saludo, confirmación de unión, latidos y actualización topológica entre vecinos</p>
            <span className="osi-transport">Canal control · Canal datos (TCP)</span>
          </div>
          <span className="osi-status done">Implementado</span>
        </div>
        <div className="osi-layer l3">
          <div className="osi-num">L3</div>
          <div className="osi-body">
            <strong>Red · Tabla de rutas</strong>
            <p>Reenvío multisalto por identidad de destino; siguiente salto = vecino TCP</p>
            <span className="osi-transport">Solo tráfico de usuario en canal datos</span>
          </div>
          <span className="osi-status done">Implementado</span>
        </div>
        <div className="osi-layer l4">
          <div className="osi-num">L4+</div>
          <div className="osi-body">
            <strong>Transporte / Aplicación</strong>
            <p>Confiabilidad TCP entre vecinos; mensajes de chat y continuidad de sesión</p>
            <span className="osi-transport">Envoltorio de usuario · epoch de conversación</span>
          </div>
          <span className="osi-status done">Integrado</span>
        </div>
        <div className="osi-rule">
          <strong>Regla de oro:</strong> la red lógica no enruta por IP final del destino.
          La IPv4 local solo indica el siguiente salto físico.
        </div>
      </div>
    ),
  },
  {
    id: 'messages',
    section: '04',
    title: 'Mensajes dirigidos y difusión',
    subtitle: 'Dos planos: unicast confiable y propagación controlada',
    content: (
      <div className="two-col">
        <div>
          <h3 className="block-title">Mensajes punto a punto</h3>
          <ul className="feature-list">
            <li><strong>Saludo y confirmación</strong> — negociación padre↔hijo en canal control</li>
            <li><strong>Datos de usuario</strong> — destino por UUID; reenvío salto a salto</li>
            <li><strong>Negociación de unión</strong> — acuerdo simétrico entre dos nodos (en diseño)</li>
            <li><strong>Apertura de canal de datos</strong> — negociación del canal de payload</li>
          </ul>
          <p className="note">
            Cada par de vecinos mantiene dos conexiones TCP independientes. Control y usuario
            <em> nunca</em> comparten el mismo canal.
          </p>
          <h3 className="block-title mt">Difusión acotada</h3>
          <ul className="feature-list">
            <li><strong>Actualización topológica</strong> — propagación con TTL entre vecinos</li>
            <li><strong>Aviso de caída</strong> — notificación de nodo caído con TTL limitado</li>
            <li><strong>Anuncio de servicios</strong> — descubrimiento inicial en la capa radio</li>
          </ul>
        </div>
        <div className="diagram-box">
          <div className="msg-flow">
            <div className="msg-node">Nodo A</div>
            <div className="msg-arrow directed">
              <span>Mensaje → destino C</span>
              <small>canal datos</small>
            </div>
            <div className="msg-node">Nodo puente B</div>
            <div className="msg-arrow directed">
              <span>Reenvío al siguiente salto</span>
              <small>consulta tabla de rutas</small>
            </div>
            <div className="msg-node">Nodo C</div>
          </div>
          <div className="broadcast-box">
            <div className="broadcast-label">Actualización topológica (difusión acotada)</div>
            <div className="broadcast-nodes">
              <span>A</span><span>↔</span><span>B</span><span>↔</span><span>C</span>
            </div>
            <small>TTL limitado · origen distinto al receptor · canal control</small>
          </div>
        </div>
      </div>
    ),
  },
  {
    id: 'bridge-disconnect',
    section: '05',
    title: 'Desconexiones en nodo puente',
    subtitle: 'Continuidad lógica sin apagar el GO propio',
    content: (
      <div className="two-col">
        <div>
          <h3 className="block-title">Detección</h3>
          <ul className="feature-list">
            <li>Latidos en canal control — timeout configurable (~15 s)</li>
            <li>Pérdida de la red legacy hacia el padre</li>
            <li>Cierre inesperado del enlace TCP upstream</li>
          </ul>
          <h3 className="block-title mt">Recuperación del puente</h3>
          <ol className="steps-list">
            <li>Cerrar enlaces de control y datos hacia el padre</li>
            <li>Limpiar vecino upstream y rutas que pasaban por él</li>
            <li>Notificar a hijos downstream la degradación</li>
            <li>Pasar a modo aislado — <strong>mantener GO propio activo</strong></li>
            <li>Re-anunciar disponibilidad · redescubrir padre alterno</li>
            <li>Re-unión por legacy + TCP — <strong>sesión de chat intacta</strong></li>
          </ol>
          <div className="highlight-box">
            Los hijos del puente caído siguen operando como subárboles autónomos;
            reconvergen cuando el puente vuelve o cuando encuentran otro padre.
          </div>
        </div>
        <div className="diagram-box">
          <div className="state-flow">
            <div className="state-node active">Puente conectado<br /><small>padre + hijos</small></div>
            <div className="state-arrow">pérdida upstream</div>
            <div className="state-node warn">Aislado<br /><small>negociando enlace</small></div>
            <div className="state-arrow">re-unión OK</div>
            <div className="state-node active">Puente restaurado<br /><small>GO propio intacto</small></div>
          </div>
          <table className="spec-table compact mt">
            <tbody>
              <tr><td>Generación de red</td><td>Solo incrementa si cae el subárbol absorbente</td></tr>
              <tr><td>Sesión de chat</td><td>Sin cambio en reconexión del puente</td></tr>
              <tr><td>Versión del árbol</td><td>Incrementa en cada unión o corte exitoso</td></tr>
              <tr><td>Canal datos caído</td><td>Reapertura negociada por canal control</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    ),
  },
  {
    id: 'unrelated-trees',
    section: '06',
    title: 'Unión de subárboles independientes',
    subtitle: 'Fusión simétrica — no obligatoria en los nodos iniciales',
    content: (
      <div className="two-col">
        <div>
          <p className="lead-text">
            Dos subárboles PCT pueden operar en paralelo (cada uno con su primer nodo encendido
            y su propia generación de red). La fusión ocurre cuando <strong>dos nodos intermedios</strong>
            —no los iniciales— se detectan y <strong>negocian</strong> cómo unirse; el rol de cada
            uno en ese acuerdo aún está por definir en el protocolo.
          </p>
          <h3 className="block-title">Mecanismo (borrador)</h3>
          <ol className="steps-list">
            <li>Dos nodos intermedios de subárboles distintos entran en alcance</li>
            <li>Intercambian mensajes de negociación de unión (simétricos)</li>
            <li>Acuerdan enlace legacy y confirmación por TCP</li>
            <li>Propagación topológica tras la unión</li>
            <li>Rutas cruzadas aprendidas por difusión acotada con TTL</li>
          </ol>
          <h3 className="block-title mt">Fusión de generaciones</h3>
          <ul className="feature-list compact">
            <li>El subárbol que absorbe conserva su generación dominante</li>
            <li>Los nodos migrantes actualizan padre lógico y profundidad</li>
            <li>Anti-ciclos: rastro de camino + límite de saltos en datos</li>
          </ul>
        </div>
        <div className="diagram-box">
          <svg viewBox="0 0 440 200" className="network-svg" aria-hidden>
            <defs>
              <marker id="mergeArrow" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
                <path d="M0,0 L6,3 L0,6 Z" fill="#ffaa00" />
              </marker>
            </defs>
            {/* Subárbol A — inicial + 3 hijos */}
            <rect x="10" y="20" width="200" height="165" rx="10" fill="none" stroke="#00d4aa" strokeWidth="2" strokeDasharray="8 4" />
            <text x="110" y="42" textAnchor="middle" fill="#00d4aa" fontSize="11">Subárbol A · gen=1</text>
            <circle cx="110" cy="72" r="18" fill="#1a2332" stroke="#00d4aa" strokeWidth="2" />
            <text x="110" y="68" textAnchor="middle" fill="#e8f4ff" fontSize="9">Inicial</text>
            <text x="110" y="80" textAnchor="middle" fill="#5a7089" fontSize="8">A₀</text>
            <line x1="110" y1="90" x2="50" y2="118" stroke="#00d4aa" strokeWidth="1.5" />
            <line x1="110" y1="90" x2="110" y2="118" stroke="#00d4aa" strokeWidth="1.5" />
            <line x1="110" y1="90" x2="170" y2="118" stroke="#00d4aa" strokeWidth="1.5" />
            <circle cx="50" cy="132" r="14" fill="#1a2332" stroke="#00d4aa" strokeWidth="1.5" />
            <text x="50" y="136" textAnchor="middle" fill="#e8f4ff" fontSize="9">A₁</text>
            <circle cx="110" cy="132" r="14" fill="#1a2332" stroke="#00d4aa" strokeWidth="1.5" />
            <text x="110" y="136" textAnchor="middle" fill="#e8f4ff" fontSize="9">A₂</text>
            <circle cx="170" cy="132" r="14" fill="#1a2332" stroke="#00d4aa" strokeWidth="2" />
            <text x="170" y="129" textAnchor="middle" fill="#ffaa00" fontSize="8">A₃</text>
            <text x="170" y="139" textAnchor="middle" fill="#ffaa00" fontSize="7">negociante</text>

            {/* Subárbol B — inicial + 3 hijos */}
            <rect x="230" y="20" width="200" height="165" rx="10" fill="none" stroke="#0088ff" strokeWidth="2" strokeDasharray="8 4" />
            <text x="330" y="42" textAnchor="middle" fill="#0088ff" fontSize="11">Subárbol B · gen=2</text>
            <circle cx="330" cy="72" r="18" fill="#1a2332" stroke="#0088ff" strokeWidth="2" />
            <text x="330" y="68" textAnchor="middle" fill="#e8f4ff" fontSize="9">Inicial</text>
            <text x="330" y="80" textAnchor="middle" fill="#5a7089" fontSize="8">B₀</text>
            <line x1="330" y1="90" x2="270" y2="118" stroke="#0088ff" strokeWidth="1.5" />
            <line x1="330" y1="90" x2="330" y2="118" stroke="#0088ff" strokeWidth="1.5" />
            <line x1="330" y1="90" x2="390" y2="118" stroke="#0088ff" strokeWidth="1.5" />
            <circle cx="270" cy="132" r="14" fill="#1a2332" stroke="#0088ff" strokeWidth="2" />
            <text x="270" y="129" textAnchor="middle" fill="#ffaa00" fontSize="8">B₁</text>
            <text x="270" y="139" textAnchor="middle" fill="#ffaa00" fontSize="7">negociante</text>
            <circle cx="330" cy="132" r="14" fill="#1a2332" stroke="#0088ff" strokeWidth="1.5" />
            <text x="330" y="136" textAnchor="middle" fill="#e8f4ff" fontSize="9">B₂</text>
            <circle cx="390" cy="132" r="14" fill="#1a2332" stroke="#0088ff" strokeWidth="1.5" />
            <text x="390" y="136" textAnchor="middle" fill="#e8f4ff" fontSize="9">B₃</text>

            {/* Unión A₃ ↔ B₁ */}
            <line x1="184" y1="132" x2="256" y2="132" stroke="#ffaa00" strokeWidth="2.5" markerEnd="url(#mergeArrow)" />
          </svg>
          <p className="diagram-caption">
            Cada subárbol: 1 nodo inicial y 3 hijos. A₃ y B₁ negocian la unión;
            los iniciales A₀ y B₀ no participan obligatoriamente.
          </p>
        </div>
      </div>
    ),
  },
  {
    id: 'library',
    section: '07',
    title: 'Biblioteca de integración',
    subtitle: 'Motor del protocolo empaquetado para apps Android',
    content: (
      <div className="two-col">
        <div>
          <h3 className="block-title">Responsabilidades por capa</h3>
          <table className="spec-table">
            <thead>
              <tr><th>Capa</th><th>Qué hace</th></tr>
            </thead>
            <tbody>
              <tr><td>API pública</td><td>Inicio, parada, envío de mensajes, eventos y topología observable</td></tr>
              <tr><td>Radio P2P</td><td>Grupo propio, descubrimiento y anuncio de servicios</td></tr>
              <tr><td>Cliente legacy</td><td>Conexión Wi‑Fi al padre seleccionado</td></tr>
              <tr><td>Enlace</td><td>Vecinos, saludos, latidos y canales duales</td></tr>
              <tr><td>Red</td><td>Tablas, propagación topológica y reenvío multisalto</td></tr>
              <tr><td>Transporte</td><td>Codificación binaria de tramas en TCP</td></tr>
            </tbody>
          </table>
          <h3 className="block-title mt">Integración en una app</h3>
          <ol className="steps-list">
            <li>Inicializar el motor con permisos Wi‑Fi y ubicación</li>
            <li>Arrancar — el bootstrap de red es automático</li>
            <li>Observar vecinos, rutas y fase en la UI de depuración</li>
            <li>Enviar mensajes de usuario por identidad destino (UUID)</li>
          </ol>
        </div>
        <div className="diagram-box">
          <div className="lib-stats">
            <div className="stat-card">
              <span className="stat-val">Control</span>
              <span className="stat-label">Canal TCP dedicado</span>
            </div>
            <div className="stat-card">
              <span className="stat-val">Datos</span>
              <span className="stat-label">Canal TCP dedicado</span>
            </div>
            <div className="stat-card">
              <span className="stat-val">UUID</span>
              <span className="stat-label">Identidad lógica 128-bit</span>
            </div>
            <div className="stat-card">
              <span className="stat-val">7</span>
              <span className="stat-label">Saltos máximos</span>
            </div>
          </div>
          <ul className="feature-list compact mt">
            <li>App demostrativa PCT Mesh incluida en el repositorio</li>
            <li>Android 12+ (API 31) · sin privilegios de administrador</li>
            <li>Limpieza de P2P al cerrar la app (anti-estado zombi)</li>
            <li>Documentación formal en docs/protocol/</li>
          </ul>
        </div>
      </div>
    ),
  },
  {
    id: 'closing',
    section: 'Fin',
    title: 'Resumen',
    subtitle: 'PCT — control topológico distribuido sobre Wi‑Fi convencional',
    content: (
      <div className="closing-grid">
        <div className="closing-item">
          <span className="closing-num">01</span>
          <strong>Arquitectura modular</strong>
          <p>Orquestación + planos Wi‑Fi + enlace/red separados</p>
        </div>
        <div className="closing-item">
          <span className="closing-num">02</span>
          <strong>GO en todo nodo</strong>
          <p>P2P conoce hijos; legacy conoce padre — cada uno su subárbol</p>
        </div>
        <div className="closing-item">
          <span className="closing-num">03</span>
          <strong>Capas emuladas</strong>
          <p>Radio · vecindad · reenvío por identidad</p>
        </div>
        <div className="closing-item">
          <span className="closing-num">04</span>
          <strong>Dirigido + difusión acotada</strong>
          <p>Datos punto a punto · control propagado con TTL</p>
        </div>
        <div className="closing-item">
          <span className="closing-num">05</span>
          <strong>Resiliencia del puente</strong>
          <p>Re-unión sin apagar GO ni perder sesión de chat</p>
        </div>
        <div className="closing-item">
          <span className="closing-num">06</span>
          <strong>Fusión de subárboles</strong>
          <p>Unión simétrica entre nodos intermedios, no solo iniciales</p>
        </div>
        <div className="closing-item wide">
          <span className="closing-num">07</span>
          <strong>Biblioteca Android</strong>
          <p>Motor listo para integrar en apps de mensajería mesh</p>
        </div>
        <div className="closing-footer">
          Especificación: docs/protocol/ · Implementación: pct/library
        </div>
      </div>
    ),
  },
]

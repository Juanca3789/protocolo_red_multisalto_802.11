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
          para redes multisalto sin root ni modificación del driver Wi‑Fi.
        </p>
        <div className="hero-meta">
          <span>co.uan.pct:core</span>
          <span>Wi‑Fi Direct GO + STA legacy</span>
          <span>TCP :8765 / :8766</span>
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
          <h3 className="block-title">Capas de la aplicación</h3>
          <ul className="feature-list">
            <li><strong>PctNode</strong> — orquestador global y máquina de estados</li>
            <li><strong>JoinManager</strong> — unión simétrica (_pct-seek / JOIN_OFFER)</li>
            <li><strong>LinkOrchestrator</strong> — vecindad L2 padre↔hijo</li>
            <li><strong>RouteOrchestrator</strong> — tabla de rutas y ForwardWorker L3</li>
            <li><strong>TopologyManager</strong> — epoch, roles, parent_nid, eventos</li>
          </ul>
          <h3 className="block-title mt">Conectividad Wi‑Fi</h3>
          <ul className="feature-list compact">
            <li><strong>P2pGoManager</strong> — createGroup(), SSID/PSK, clientList</li>
            <li><strong>LegacyStaManager</strong> — WifiNetworkSpecifier al padre</li>
            <li><strong>P2pDnsSdManager</strong> — _pct-ctrl / _pct-seek</li>
            <li><strong>TcpControlPlane</strong> — framing PCT1, I/O dual canal</li>
          </ul>
        </div>
        <div className="diagram-box">
          <div className="arch-stack">
            <div className="arch-layer app">Aplicación · Chat / UI</div>
            <div className="arch-arrow">▼</div>
            <div className="arch-layer orch">ProtocolOrchestrator · Join · Route · Topo</div>
            <div className="arch-arrow">▼</div>
            <div className="arch-layer wifi">GO P2P · STA legacy · DNS-SD · TCP</div>
            <div className="arch-arrow">▼</div>
            <div className="arch-layer radio">802.11 · Wi‑Fi Direct + infra</div>
          </div>
          <p className="diagram-caption">
            Regla: mutación de tablas de rutas desde un solo actor (RouteOrchestrator).
          </p>
        </div>
      </div>
    ),
  },
  {
    id: 'connection-mode',
    section: '02',
    title: 'Modo de conexión',
    subtitle: 'Todo nodo es GO; el árbol se forma solo por STA legacy',
    content: (
      <div className="two-col">
        <div>
          <div className="principle-card">
            <span className="principle-icon">⛔</span>
            <div>
              <strong>Prohibido</strong> WifiP2pManager.connect() para topología.
              No hay Group Client como enlace del árbol.
            </div>
          </div>
          <h3 className="block-title">Dos interfaces por nodo BRIDGE</h3>
          <table className="spec-table">
            <thead>
              <tr><th>Interfaz</th><th>Objeto</th><th>Conoce</th></tr>
            </thead>
            <tbody>
              <tr>
                <td><span className="tag upstream">UPSTREAM</span></td>
                <td>Red STA (WifiNetwork)</td>
                <td>IP gateway del padre · ej. 192.168.49.1</td>
              </tr>
              <tr>
                <td><span className="tag downstream">DOWNSTREAM</span></td>
                <td>GO P2P propio</td>
                <td>IPs DHCP de hijos · ej. 192.168.49.2</td>
              </tr>
            </tbody>
          </table>
          <h3 className="block-title mt">Bootstrap típico</h3>
          <ol className="steps-list">
            <li>Scan DNS-SD → credenciales SSID/PSK + UUID padre</li>
            <li>STA legacy al SoftAP del padre</li>
            <li>createGroup() — GO propio (BRIDGE)</li>
            <li>TCP L2 al gateway STA · HELLO → JOIN → DATA</li>
          </ol>
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
            <text x="210" y="52" textAnchor="middle" fill="#0a0e17" fontSize="14" fontWeight="600">ROOT (GO)</text>
            <rect x="30" y="200" width="120" height="56" rx="8" fill="#1a2332" stroke="#00d4aa" strokeWidth="2" />
            <text x="90" y="232" textAnchor="middle" fill="#e8f4ff" fontSize="13">BRIDGE</text>
            <rect x="270" y="200" width="120" height="56" rx="8" fill="#1a2332" stroke="#0088ff" strokeWidth="2" />
            <text x="330" y="232" textAnchor="middle" fill="#e8f4ff" fontSize="13">LEAF</text>
            <line x1="210" y1="76" x2="90" y2="200" stroke="#00d4aa" strokeWidth="2" strokeDasharray="6 4" />
            <line x1="210" y1="76" x2="330" y2="200" stroke="#0088ff" strokeWidth="2" strokeDasharray="6 4" />
            <text x="130" y="140" fill="#7eb8ff" fontSize="11">STA legacy</text>
            <text x="290" y="140" fill="#7eb8ff" fontSize="11">STA legacy</text>
            <text x="90" y="280" textAnchor="middle" fill="#5a7089" fontSize="10">GO propio</text>
            <text x="330" y="280" textAnchor="middle" fill="#5a7089" fontSize="10">GO propio</text>
          </svg>
          <p className="diagram-caption">
            Enlace padre→hijo = asociación STA al SSID/PSK del GO padre. Identidad lógica = UUID, no MAC.
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
            <p>createGroup(), STA legacy, DNS-SD bootstrap (_pct-ctrl)</p>
            <span className="osi-transport">802.11 · Wi‑Fi Direct + infra</span>
          </div>
          <span className="osi-status done">Operativo</span>
        </div>
        <div className="osi-layer l2">
          <div className="osi-num">L2</div>
          <div className="osi-body">
            <strong>Enlace · NeighborRegistry</strong>
            <p>HELLO, JOIN_COMMIT, PING/PONG, TOPO entre vecinos directos</p>
            <span className="osi-transport">TCP :8765 control · :8766 datos</span>
          </div>
          <span className="osi-status done">Implementado</span>
        </div>
        <div className="osi-layer l3">
          <div className="osi-num">L3</div>
          <div className="osi-body">
            <strong>Red · RoutingTable</strong>
            <p>Reenvío multisalto por destination_uuid; next_hop = vecino TCP</p>
            <span className="osi-transport">Solo canal datos :8766</span>
          </div>
          <span className="osi-status done">Implementado</span>
        </div>
        <div className="osi-layer l4">
          <div className="osi-num">L4+</div>
          <div className="osi-body">
            <strong>Transporte / Aplicación</strong>
            <p>Confiabilidad TCP vecino-a-vecino; payloads type=user al chat</p>
            <span className="osi-transport">PctUserEnvelope · session_epoch</span>
          </div>
          <span className="osi-status done">Integrado</span>
        </div>
        <div className="osi-rule">
          <strong>Regla de oro:</strong> L3 nunca enruta por IP de destino final.
          La IPv4 local es dato de enlace para el siguiente salto.
        </div>
      </div>
    ),
  },
  {
    id: 'messages',
    section: '04',
    title: 'Mensajes dirigidos y Broadcast',
    subtitle: 'Dos planos: unicast confiable y difusión controlada',
    content: (
      <div className="two-col">
        <div>
          <h3 className="block-title">Mensajes dirigidos (unicast)</h3>
          <ul className="feature-list">
            <li><strong>HELLO / JOIN_COMMIT</strong> — padre↔hijo por TCP control</li>
            <li><strong>DATA (0x0A)</strong> — destino UUID en envelope; ForwardWorker reenvía hop-a-hop</li>
            <li><strong>JOIN_OFFER / JOIN_ACCEPT</strong> — unión simétrica nodo a nodo</li>
            <li><strong>DATA_CHANNEL_OPEN/ACK</strong> — negociación canal :8766</li>
          </ul>
          <p className="note">
            Cada par vecino mantiene dos sockets TCP independientes. Control y usuario
            <em> nunca</em> comparten el mismo socket.
          </p>
          <h3 className="block-title mt">Broadcast / difusión</h3>
          <ul className="feature-list">
            <li><strong>TOPO_UPDATE</strong> — flood con TTL entre vecinos (gossip de rutas)</li>
            <li><strong>NODE_DOWN</strong> — notificación de caída propagada con TTL</li>
            <li><strong>DNS-SD</strong> — anuncio _pct-ctrl / _pct-seek (bootstrap L1)</li>
          </ul>
        </div>
        <div className="diagram-box">
          <div className="msg-flow">
            <div className="msg-node">A (ROOT)</div>
            <div className="msg-arrow directed">
              <span>DATA → UUID(C)</span>
              <small>unicast :8766</small>
            </div>
            <div className="msg-node">B (BRIDGE)</div>
            <div className="msg-arrow directed">
              <span>forward DATA</span>
              <small>next_hop lookup</small>
            </div>
            <div className="msg-node">C (LEAF)</div>
          </div>
          <div className="broadcast-box">
            <div className="broadcast-label">TOPO_UPDATE (broadcast acotado)</div>
            <div className="broadcast-nodes">
              <span>A</span><span>↔</span><span>B</span><span>↔</span><span>C</span>
            </div>
            <small>ttl=7 · origin ≠ self · canal control :8765</small>
          </div>
        </div>
      </div>
    ),
  },
  {
    id: 'bridge-disconnect',
    section: '05',
    title: 'Desconexiones en Bridge',
    subtitle: 'Continuidad lógica sin reiniciar el GO propio',
    content: (
      <div className="two-col">
        <div>
          <h3 className="block-title">Detección</h3>
          <ul className="feature-list">
            <li>PING/PONG en canal control — timeout T_PONG = 15 s</li>
            <li>Pérdida STA upstream — callback Network.onLost</li>
            <li>EOF en socket TCP control upstream</li>
          </ul>
          <h3 className="block-title mt">Secuencia de recuperación BRIDGE</h3>
          <ol className="steps-list">
            <li>Cerrar ctrl_socket y data_socket upstream</li>
            <li>Purgar NeighborRegistry UPSTREAM + rutas vía padre</li>
            <li>Emitir NODE_DOWN hacia hijos downstream</li>
            <li>Transitar a ISLAND — mantener GO propio activo</li>
            <li>Re-anunciar _pct-seek · redescubrir padre alterno</li>
            <li>Re-JOIN vía STA + TCP L2 — <strong>session_epoch intacto</strong></li>
          </ol>
          <div className="highlight-box">
            Los hijos del BRIDGE caído siguen operativos como sub-árboles;
            re-convergen cuando el BRIDGE se re-incorpora o un nuevo padre asume rutas.
          </div>
        </div>
        <div className="diagram-box">
          <div className="state-flow">
            <div className="state-node active">R_GO_UPSTREAM<br /><small>BRIDGE</small></div>
            <div className="state-arrow">upstream lost</div>
            <div className="state-node warn">R_ISLAND_SEEK<br /><small>_pct-seek</small></div>
            <div className="state-arrow">re-JOIN OK</div>
            <div className="state-node active">R_GO_UPSTREAM<br /><small>restaurado</small></div>
          </div>
          <table className="spec-table compact mt">
            <tbody>
              <tr><td>epoch red</td><td>++ solo si ROOT confirmado caído</td></tr>
              <tr><td>session_epoch</td><td>sin cambio en reconexión</td></tr>
              <tr><td>tree_version</td><td>++ en cada JOIN/CUT exitoso</td></tr>
              <tr><td>Canal datos caído</td><td>DATA_CHANNEL_RESET vía :8765</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    ),
  },
  {
    id: 'unrelated-trees',
    section: '06',
    title: 'Unión de unrelated trees',
    subtitle: 'Unión simétrica entre sub-redes independientes',
    content: (
      <div className="two-col">
        <div>
          <p className="lead-text">
            Dos árboles PCT operando de forma independiente (distintos ROOT, distintos epoch)
            pueden fusionarse cuando un nodo ISLAND de uno se incorpora al otro vía unión simétrica.
          </p>
          <h3 className="block-title">Mecanismo</h3>
          <ol className="steps-list">
            <li>ISLAND de árbol B anuncia <code>_pct-seek._tcp</code></li>
            <li>BRIDGE de árbol A detecta seeker y envía JOIN_OFFER</li>
            <li>Negociación JOIN_ACCEPT → STA al GO de A</li>
            <li>JOIN_COMMIT + TOPO_UPDATE — tree_version++ en A</li>
            <li>Rutas cruzadas aprendidas por flood TOPO con TTL</li>
          </ol>
          <h3 className="block-title mt">Fusión de epoch</h3>
          <ul className="feature-list compact">
            <li>Árbol absorbente conserva epoch dominante</li>
            <li>Nodos migrantes actualizan parent_nid y hop</li>
            <li>Anti-ciclos: path_trace + hop_limit en DATA</li>
          </ul>
        </div>
        <div className="diagram-box">
          <svg viewBox="0 0 420 280" className="network-svg" aria-hidden>
            <rect x="20" y="30" width="160" height="100" rx="10" fill="none" stroke="#00d4aa" strokeWidth="2" strokeDasharray="8 4" />
            <text x="100" y="55" textAnchor="middle" fill="#00d4aa" fontSize="12">Árbol A · epoch=1</text>
            <circle cx="100" cy="90" r="22" fill="#1a2332" stroke="#00d4aa" strokeWidth="2" />
            <text x="100" y="95" textAnchor="middle" fill="#e8f4ff" fontSize="11">ROOT A</text>
            <rect x="240" y="30" width="160" height="100" rx="10" fill="none" stroke="#0088ff" strokeWidth="2" strokeDasharray="8 4" />
            <text x="320" y="55" textAnchor="middle" fill="#0088ff" fontSize="12">Árbol B · epoch=2</text>
            <circle cx="320" cy="90" r="22" fill="#1a2332" stroke="#0088ff" strokeWidth="2" />
            <text x="320" y="95" textAnchor="middle" fill="#e8f4ff" fontSize="11">ROOT B</text>
            <circle cx="320" cy="200" r="20" fill="#1a2332" stroke="#ffaa00" strokeWidth="2" />
            <text x="320" y="205" textAnchor="middle" fill="#ffaa00" fontSize="10">ISLAND</text>
            <line x1="320" y1="112" x2="320" y2="180" stroke="#0088ff" strokeWidth="1.5" strokeDasharray="4 3" />
            <line x1="300" y1="200" x2="130" y2="100" stroke="#ffaa00" strokeWidth="2" markerEnd="url(#arrow)" />
            <text x="210" y="165" fill="#ffaa00" fontSize="11">JOIN_OFFER → STA → merge</text>
            <circle cx="100" cy="200" r="20" fill="#1a2332" stroke="#00d4aa" strokeWidth="2" />
            <text x="100" y="205" textAnchor="middle" fill="#e8f4ff" fontSize="10">BRIDGE A</text>
            <line x1="100" y1="112" x2="100" y2="180" stroke="#00d4aa" strokeWidth="1.5" />
          </svg>
          <p className="diagram-caption">
            Unión tardía: nodos ya operativos no reinician SSID. El tráfico entre árboles
            fluye por el BRIDGE de unión.
          </p>
        </div>
      </div>
    ),
  },
  {
    id: 'library',
    section: '07',
    title: 'Biblioteca lib-pct-core',
    subtitle: 'Artefacto Maven co.uan.pct:core · API pública Android',
    content: (
      <div className="two-col">
        <div>
          <h3 className="block-title">Módulos internos</h3>
          <table className="spec-table">
            <thead>
              <tr><th>Paquete</th><th>Responsabilidad</th></tr>
            </thead>
            <tbody>
              <tr><td><code>api/</code></td><td>PctNode, PctConfig, TopologySnapshot, eventos</td></tr>
              <tr><td><code>internal/p2p/</code></td><td>GoRepository, DnsSdRepository, P2pChannelHolder</td></tr>
              <tr><td><code>internal/sta/</code></td><td>LegacyStaRepository · WifiNetwork activa al padre</td></tr>
              <tr><td><code>internal/link/</code></td><td>LinkOrchestrator, NeighborRegistry</td></tr>
              <tr><td><code>internal/route/</code></td><td>RouteOrchestrator, RoutingTable, ForwardWorker</td></tr>
              <tr><td><code>internal/tcp/</code></td><td>PctFrameCodec, sockets :8765/:8766</td></tr>
            </tbody>
          </table>
          <h3 className="block-title mt">API de integración</h3>
          <pre className="code-block">{`PctNode node = PctCore.create();
node.init(context, PctConfig.DEFAULT);
node.start();  // bootstrap automático
node.sendUser(destUuid, payload);
node.topology.collect { ... }`}</pre>
        </div>
        <div className="diagram-box">
          <div className="lib-stats">
            <div className="stat-card">
              <span className="stat-val">8765</span>
              <span className="stat-label">Control TCP</span>
            </div>
            <div className="stat-card">
              <span className="stat-val">8766</span>
              <span className="stat-label">Datos TCP</span>
            </div>
            <div className="stat-card">
              <span className="stat-val">UUID</span>
              <span className="stat-label">Identidad 128-bit</span>
            </div>
            <div className="stat-card">
              <span className="stat-val">7</span>
              <span className="stat-label">Hop limit max</span>
            </div>
          </div>
          <ul className="feature-list compact mt">
            <li>App demo: <code>pct/app/app-demo</code> (PCT Mesh)</li>
            <li>Publicación: <code>publishToMavenLocal</code></li>
            <li>minSdk 31 · targetSdk 36</li>
            <li>Teardown P2P anti-zombi al cerrar app</li>
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
          <p>Orquestador + planos Wi‑Fi + L2/L3 separados</p>
        </div>
        <div className="closing-item">
          <span className="closing-num">02</span>
          <strong>GO homogéneo + STA</strong>
          <p>Dos redes: P2P conoce hijos, STA conoce padre</p>
        </div>
        <div className="closing-item">
          <span className="closing-num">03</span>
          <strong>OSI emulado</strong>
          <p>L1 radio · L2 vecindad · L3 reenvío UUID</p>
        </div>
        <div className="closing-item">
          <span className="closing-num">04</span>
          <strong>Unicast + TOPO flood</strong>
          <p>DATA dirigido · control broadcast acotado</p>
        </div>
        <div className="closing-item">
          <span className="closing-num">05</span>
          <strong>Resiliencia BRIDGE</strong>
          <p>Re-JOIN sin perder GO ni session_epoch</p>
        </div>
        <div className="closing-item">
          <span className="closing-num">06</span>
          <strong>Fusión de árboles</strong>
          <p>Unión simétrica _pct-seek entre sub-redes</p>
        </div>
        <div className="closing-item wide">
          <span className="closing-num">07</span>
          <strong>lib-pct-core</strong>
          <p>Biblioteca Android lista para integrar en apps de mensajería mesh</p>
        </div>
        <div className="closing-footer">
          Documentación: docs/protocol/ · Prototipo: pct/library/lib-pct-core
        </div>
      </div>
    ),
  },
]

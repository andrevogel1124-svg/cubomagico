import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.util.*;
import java.util.List;

/**
 * Simulador de Cubo Mágico 3D em Java puro (Swing / Java2D).
 * Não precisa de nenhuma biblioteca externa: o 3D é feito com projeção
 * em perspectiva própria e o algoritmo do pintor (faces ordenadas por distância).
 *
 * Compilar:  javac -encoding UTF-8 CuboMagico3D.java
 * Executar:  java CuboMagico3D
 *
 * CONTROLES
 *   Clique em um bloco (adesivo) do cubo ......... seleciona o bloco
 *   W / A / S / D ................................ gira a camada do bloco para
 *                                                  cima / esquerda / baixo / direita
 *                                                  (sempre relativo ao que você vê na tela)
 *   Clique no fundo .............................. remove a seleção
 *   Arrastar com o mouse ......................... gira a visão (câmera)
 *   Roda do mouse / setas / Home ................. zoom / girar visão / resetar vista
 *   ESPAÇO = embaralhar | ENTER = resolver | ESC = reiniciar
 */
public class CuboMagico3D extends JPanel {

    // ------------------------------------------------------------------
    //  Constantes
    // ------------------------------------------------------------------
    // Índices das direções: 0:+X (direita) 1:-X (esquerda) 2:+Y (cima)
    //                       3:-Y (baixo)   4:+Z (frente)   5:-Z (trás)
    static final int[][] DIRS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    static final Color[] CORES = {
            new Color(255, 112, 0),    // direita  - laranja
            new Color(196, 30, 58),    // esquerda - vermelho
            new Color(255, 213, 0),    // cima     - amarelo
            new Color(250, 250, 250),  // baixo    - BRANCO (face branca começa virada para baixo)
            new Color(0, 155, 72),     // frente   - verde
            new Color(0, 70, 173)      // trás     - azul
    };
    static final Color PRETO = new Color(16, 16, 18);
    static final double DIST = 16.0;                       // distância da câmera
    static final double[] LUZ = normalizar(new double[]{0.35, 0.7, 0.62});

    // ------------------------------------------------------------------
    //  Modelo do cubo
    // ------------------------------------------------------------------
    /** Um dos 27 cubinhos: posição inteira (-1..1), orientação (matriz 3x3) e cores. */
    static class Cubinho {
        int[] pos;
        int[][] rot = identidade();
        Color[] cores = new Color[6];   // cor em cada face LOCAL (antes de rotacionar)

        Cubinho(int x, int y, int z) {
            pos = new int[]{x, y, z};
            for (int d = 0; d < 6; d++)
                if (DIRS[d][0] * x + DIRS[d][1] * y + DIRS[d][2] * z == 1) cores[d] = CORES[d];
        }
    }

    /** Um quarto de volta em uma camada. */
    static class Movimento {
        final int eixo, camada, dir;           // dir = +1 / -1 (regra da mão direita)
        final boolean registrar, usuario;

        Movimento(int eixo, int camada, int dir, boolean registrar, boolean usuario) {
            this.eixo = eixo; this.camada = camada; this.dir = dir;
            this.registrar = registrar; this.usuario = usuario;
        }

        Movimento inverso(boolean registrar) {
            return new Movimento(eixo, camada, -dir, registrar, false);
        }
    }

    /** Notação padrão (usada no embaralhamento): R L U D F B. */
    static Movimento criar(char letra, boolean antiHorario, boolean registrar, boolean usuario) {
        int eixo, camada, dir;
        switch (letra) {
            case 'R': eixo = 0; camada = 1;  dir = -1; break;
            case 'L': eixo = 0; camada = -1; dir = 1;  break;
            case 'U': eixo = 1; camada = 1;  dir = -1; break;
            case 'D': eixo = 1; camada = -1; dir = 1;  break;
            case 'F': eixo = 2; camada = 1;  dir = -1; break;
            default:  eixo = 2; camada = -1; dir = 1;  break; // 'B'
        }
        if (antiHorario) dir = -dir;
        return new Movimento(eixo, camada, dir, registrar, usuario);
    }

    // ------------------------------------------------------------------
    //  Estado
    // ------------------------------------------------------------------
    final List<Cubinho> cubos = new ArrayList<>();
    final Deque<Movimento> fila = new ArrayDeque<>();
    final List<Movimento> historico = new ArrayList<>();
    Movimento atual;
    double progresso;
    long ultimoTick = System.nanoTime();

    double yaw, pitch, zoom;
    int duracaoMs = 375;

    int movimentos;
    boolean armado, cronometrando;
    long inicio, tempoFinal;
    String mensagem = "";

    // seleção por clique
    Cubinho selecionado;            // bloco selecionado (acompanha o bloco quando ele se move)
    int selDir;                     // face LOCAL do bloco que foi clicada
    List<Face> facesVisiveis = new ArrayList<>();   // do último desenho (para o clique)

    // mouse
    int pressX, pressY, mouseX, mouseY;
    boolean arrastou;

    public CuboMagico3D() {
        setBackground(new Color(20, 22, 34));
        resetarVista();
        reiniciar();

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                pressX = mouseX = e.getX();
                pressY = mouseY = e.getY();
                arrastou = false;
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (!arrastou) {   // só vira "arrastar" depois de passar de alguns pixels
                    if (Math.abs(e.getX() - pressX) + Math.abs(e.getY() - pressY) < 5) return;
                    arrastou = true;
                    mouseX = e.getX(); mouseY = e.getY();
                    return;
                }
                yaw += (e.getX() - mouseX) * 0.010;
                pitch += (e.getY() - mouseY) * 0.010;
                pitch = Math.max(-1.55, Math.min(1.55, pitch));
                mouseX = e.getX(); mouseY = e.getY();
                repaint();
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (!arrastou && SwingUtilities.isLeftMouseButton(e)) selecionarEm(e.getX(), e.getY());
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                zoom *= (1 - 0.08 * e.getPreciseWheelRotation());
                zoom = Math.max(0.5, Math.min(2.2, zoom));
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);

        new javax.swing.Timer(16, e -> tick()).start();
    }

    // ------------------------------------------------------------------
    //  Seleção e giro pelo W A S D
    // ------------------------------------------------------------------
    /** Seleciona o adesivo sob o ponto (x, y) da tela; clicar fora remove a seleção. */
    void selecionarEm(int x, int y) {
        selecionado = null;
        for (int i = facesVisiveis.size() - 1; i >= 0; i--) {      // da mais próxima para a mais distante
            Face fa = facesVisiveis.get(i);
            if (fa.corpo != null && fa.corpo.contains(x, y)) {
                if (fa.cor != null) { selecionado = fa.cubo; selDir = fa.dirLocal; }
                break;
            }
        }
        repaint();
    }

    /**
     * Gira a camada do bloco selecionado na direção da TELA indicada pela tecla:
     * W = cima, S = baixo, A = esquerda, D = direita.
     *
     * Como pode haver movimentos ainda na fila, o estado do cubo é "previsto"
     * aplicando a animação atual e a fila sobre uma cópia das posições.
     */
    void girarSelecionado(char tecla) {
        if (selecionado == null) return;
        int n = cubos.size();
        int idx = cubos.indexOf(selecionado);
        int[][] pos = new int[n][];
        int[][][] rot = new int[n][][];
        for (int i = 0; i < n; i++) {
            Cubinho c = cubos.get(i);
            pos[i] = c.pos.clone();
            rot[i] = new int[][]{c.rot[0].clone(), c.rot[1].clone(), c.rot[2].clone()};
        }
        List<Movimento> pendentes = new ArrayList<>();
        if (atual != null) pendentes.add(atual);
        pendentes.addAll(fila);
        for (Movimento m : pendentes) {
            int[][] q = quartoDeVolta(m.eixo, m.dir);
            for (int i = 0; i < n; i++) {
                if (pos[i][m.eixo] == m.camada) {
                    pos[i] = mulV(q, pos[i]);
                    rot[i] = mul(q, rot[i]);
                }
            }
        }

        // normal (no mundo) da face clicada
        int[] normal = mulV(rot[idx], DIRS[selDir]);

        // direção da tela -> direção no mundo (transposta da matriz de vista)
        double[][] vista = matrizVista();
        double[] dv = {tecla == 'D' ? 1 : tecla == 'A' ? -1 : 0, tecla == 'W' ? 1 : tecla == 'S' ? -1 : 0, 0};
        double[] dw = new double[3];
        for (int i = 0; i < 3; i++)
            dw[i] = vista[0][i] * dv[0] + vista[1][i] * dv[1] + vista[2][i] * dv[2];

        // escolhe o eixo do cubo (perpendicular à normal) mais alinhado com essa direção
        int melhor = -1;
        double mv = 1e-6;
        for (int i = 0; i < 3; i++) {
            if (normal[i] != 0) continue;
            if (Math.abs(dw[i]) > mv) { mv = Math.abs(dw[i]); melhor = i; }
        }
        if (melhor < 0) return;
        int[] ds = new int[3];
        ds[melhor] = dw[melhor] > 0 ? 1 : -1;

        // eixo de rotação = normal x direção  (o bloco anda na direção pedida)
        int[] a = {normal[1] * ds[2] - normal[2] * ds[1],
                   normal[2] * ds[0] - normal[0] * ds[2],
                   normal[0] * ds[1] - normal[1] * ds[0]};
        int eixo = a[0] != 0 ? 0 : a[1] != 0 ? 1 : 2;
        int dir = a[eixo];
        int camada = pos[idx][eixo];
        fila.add(new Movimento(eixo, camada, dir, true, true));
    }

    // ------------------------------------------------------------------
    //  Ações
    // ------------------------------------------------------------------
    void resetarVista() {
        yaw = Math.toRadians(-35);
        pitch = Math.toRadians(28);
        zoom = 1.0;
        repaint();
    }

    void reiniciar() {
        fila.clear();
        historico.clear();
        atual = null;
        progresso = 0;
        selecionado = null;
        facesVisiveis = new ArrayList<>();
        cubos.clear();
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++)
                    cubos.add(new Cubinho(x, y, z));
        movimentos = 0;
        armado = cronometrando = false;
        mensagem = "";
        repaint();
    }

    void embaralhar() {
        Random r = new Random();
        String letras = "UDLRFB";
        int ultimoEixo = -1;
        for (int i = 0; i < 22; i++) {
            char l;
            int eixo;
            do {
                l = letras.charAt(r.nextInt(letras.length()));
                eixo = (l == 'R' || l == 'L') ? 0 : (l == 'U' || l == 'D') ? 1 : 2;
            } while (eixo == ultimoEixo);
            ultimoEixo = eixo;
            fila.add(criar(l, r.nextBoolean(), true, false));
        }
        armado = true;
        cronometrando = false;
        movimentos = 0;
        mensagem = "Embaralhando...";
    }

    /** Resolve desfazendo, na ordem inversa, todos os movimentos feitos desde o cubo resolvido. */
    void resolver() {
        List<Movimento> futuro = new ArrayList<>(historico);
        List<Movimento> pendentes = new ArrayList<>();
        if (atual != null) pendentes.add(atual);
        pendentes.addAll(fila);
        for (Movimento m : pendentes) {           // simula o histórico após a fila atual
            if (m.registrar) futuro.add(m);
            else if (!futuro.isEmpty()) futuro.remove(futuro.size() - 1);
        }
        for (int i = futuro.size() - 1; i >= 0; i--) fila.add(futuro.get(i).inverso(false));
        cronometrando = false;
        armado = false;
        movimentos = 0;
        mensagem = futuro.isEmpty() ? "" : "Resolvendo...";
    }

    // ------------------------------------------------------------------
    //  Animação / lógica
    // ------------------------------------------------------------------
    void tick() {
        long agora = System.nanoTime();
        double dt = (agora - ultimoTick) / 1e6;
        ultimoTick = agora;
        boolean mudou = false;

        if (atual == null && !fila.isEmpty()) {
            atual = fila.poll();
            progresso = 0;
            if (atual.usuario && armado && !cronometrando) {
                cronometrando = true;
                inicio = System.currentTimeMillis();
                armado = false;
                mensagem = "";
            }
        }
        if (atual != null) {
            double dur = fila.isEmpty() ? duracaoMs : duracaoMs * 0.45;   // acelera quando há fila
            progresso += dt / dur;
            if (progresso >= 1) concluirMovimento();
            mudou = true;
        }
        if (mudou || cronometrando) repaint();
    }

    void concluirMovimento() {
        aplicar(atual);
        if (atual.registrar) historico.add(atual);
        else if (!historico.isEmpty()) historico.remove(historico.size() - 1);
        if (atual.usuario) movimentos++;
        atual = null;
        progresso = 0;

        if (fila.isEmpty()) {
            if (mensagem.equals("Embaralhando...")) {
                mensagem = "Embaralhado! O tempo conta a partir do primeiro movimento.";
            } else if (estaResolvido()) {
                if (cronometrando) {
                    cronometrando = false;
                    tempoFinal = System.currentTimeMillis() - inicio;
                    mensagem = "Parabéns! Resolvido em " + formatarTempo(tempoFinal)
                            + " com " + movimentos + " movimentos.";
                } else if (mensagem.equals("Resolvendo...")) {
                    mensagem = "Cubo resolvido!";
                }
            }
        }
    }

    /** Aplica definitivamente o quarto de volta ao modelo (posição e orientação inteiras). */
    void aplicar(Movimento m) {
        int[][] q = quartoDeVolta(m.eixo, m.dir);
        for (Cubinho c : cubos) {
            if (c.pos[m.eixo] == m.camada) {
                c.pos = mulV(q, c.pos);
                c.rot = mul(q, c.rot);
            }
        }
    }

    boolean estaResolvido() {
        Color[] porFace = new Color[6];
        for (Cubinho c : cubos) {
            for (int d = 0; d < 6; d++) {
                if (c.cores[d] == null) continue;
                int[] n = mulV(c.rot, DIRS[d]);
                int idx = indiceDir(n);
                if (porFace[idx] == null) porFace[idx] = c.cores[d];
                else if (!porFace[idx].equals(c.cores[d])) return false;
            }
        }
        return true;
    }

    static int indiceDir(int[] n) {
        for (int d = 0; d < 6; d++) if (Arrays.equals(DIRS[d], n)) return d;
        return 0;
    }

    static String formatarTempo(long ms) {
        long min = ms / 60000;
        double seg = (ms % 60000) / 1000.0;
        return String.format("%d:%04.1f", min, seg);
    }

    // ------------------------------------------------------------------
    //  Renderização 3D
    // ------------------------------------------------------------------
    static class Face {
        double[][] pts;      // 4 vértices (espaço da câmera)
        double[] centro;
        double[] normal;
        Color cor;           // null = face interna (só preto)
        double dist2;
        Cubinho cubo;        // para o clique
        int dirLocal;
        boolean selecionada;
        Path2D corpo;        // contorno na tela
    }

    double[][] matrizVista() {
        return mul(rotD(0, pitch), rotD(1, yaw));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int w = getWidth(), h = getHeight();
        g.setPaint(new GradientPaint(0, 0, new Color(40, 46, 70), 0, h, new Color(9, 11, 20)));
        g.fillRect(0, 0, w, h);

        double f = Math.min(w, h) * 2.6 * zoom;
        double cx = w / 2.0, cy = h / 2.0;

        double[][] vista = matrizVista();
        double[][] ra = null;
        if (atual != null) ra = rotD(atual.eixo, atual.dir * (Math.PI / 2) * suave(progresso));

        // 1) Monta todas as faces visíveis
        List<Face> faces = new ArrayList<>(162);
        int[][] sinais = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (Cubinho c : cubos) {
            double[][] rm = (atual != null && c.pos[atual.eixo] == atual.camada) ? ra : null;
            for (int d = 0; d < 6; d++) {
                int a = d / 2, b = (a + 1) % 3, cc = (a + 2) % 3;
                double sg = DIRS[d][a];

                double[] n = mulD(vista, rm != null ? mulD(rm, mulD(c.rot, DIRS[d]))
                                                    : mulD(c.rot, DIRS[d]));
                double[] vc = new double[3];
                vc[a] = sg * 0.5;
                double[] centro = transformar(c, vc, rm, vista);

                // descarta faces de costas para a câmera
                double toCam = -centro[0] * n[0] - centro[1] * n[1] + (DIST - centro[2]) * n[2];
                if (toCam <= 0) continue;

                Face fa = new Face();
                fa.pts = new double[4][];
                for (int k = 0; k < 4; k++) {
                    double[] v = new double[3];
                    v[a] = sg * 0.5;
                    v[b] = sinais[k][0] * 0.5;
                    v[cc] = sinais[k][1] * 0.5;
                    fa.pts[k] = transformar(c, v, rm, vista);
                }
                fa.centro = centro;
                fa.normal = n;
                fa.cor = c.cores[d];
                fa.cubo = c;
                fa.dirLocal = d;
                fa.selecionada = (c == selecionado && d == selDir && c.cores[d] != null);
                fa.dist2 = centro[0] * centro[0] + centro[1] * centro[1]
                        + (DIST - centro[2]) * (DIST - centro[2]);
                faces.add(fa);
            }
        }

        // 2) Algoritmo do pintor: de longe para perto
        faces.sort((p, q) -> Double.compare(q.dist2, p.dist2));

        // 3) Desenha
        for (Face fa : faces) {
            double lum = 0.60 + 0.40 * Math.max(0, fa.normal[0] * LUZ[0] + fa.normal[1] * LUZ[1] + fa.normal[2] * LUZ[2]);
            if (fa.selecionada) lum = Math.min(1.2, lum + 0.15);

            fa.corpo = caminho(fa.pts, f, cx, cy);
            g.setColor(escurecer(PRETO, lum));
            g.fill(fa.corpo);

            if (fa.cor != null) {
                Path2D st = caminho(encolher(fa, 0.80), f, cx, cy);
                g.setColor(escurecer(fa.cor, lum));
                g.fill(st);
                float espessura = (float) (0.075 * f / (DIST - fa.centro[2]));   // cantos arredondados
                g.setStroke(new BasicStroke(espessura, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(st);

                if (fa.selecionada) {   // moldura de destaque
                    Path2D moldura = caminho(encolher(fa, 0.95), f, cx, cy);
                    g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(new Color(0, 0, 0, 150));
                    g.draw(moldura);
                    g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(new Color(255, 255, 255));
                    g.draw(moldura);
                }
            }
        }
        facesVisiveis = faces;     // usado para saber onde o usuário clicou

        desenharInterface(g, w, h);
    }

    static double[][] encolher(Face fa, double k) {
        double[][] r = new double[4][3];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 3; j++)
                r[i][j] = fa.centro[j] + (fa.pts[i][j] - fa.centro[j]) * k;
        return r;
    }

    void desenharInterface(Graphics2D g, int w, int h) {
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.setColor(new Color(235, 238, 250));
        long ms = cronometrando ? System.currentTimeMillis() - inicio : tempoFinal;
        String info = "Movimentos: " + movimentos + "     Tempo: " + formatarTempo(ms);
        g.drawString(info, 18, 30);

        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        if (!mensagem.isEmpty()) {
            g.setColor(new Color(255, 213, 79));
            g.drawString(mensagem, 18, 54);
        }
        g.setColor(new Color(130, 200, 255));
        g.drawString(selecionado == null
                ? "Clique em um bloco do cubo para selecioná-lo."
                : "Bloco selecionado: use W (cima), A (esquerda), S (baixo) ou D (direita) para girá-lo.",
                18, 78);

        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(150, 158, 185));
        g.drawString("Clique = selecionar bloco  |  W A S D = girar  |  Espaço = embaralhar  |  "
                + "Enter = resolver  |  Esc = reiniciar", 18, h - 32);
        g.drawString("Arrastar com o mouse = girar a visão  |  Roda = zoom  |  Setas = girar a visão  |  Home = resetar vista",
                18, h - 14);
    }

    static double[] transformar(Cubinho c, double[] v, double[][] anim, double[][] vista) {
        double[] p = mulD(c.rot, v);
        p[0] += c.pos[0]; p[1] += c.pos[1]; p[2] += c.pos[2];
        if (anim != null) p = mulD(anim, p);
        return mulD(vista, p);
    }

    static Path2D caminho(double[][] pts, double f, double cx, double cy) {
        Path2D.Double p = new Path2D.Double();
        for (int i = 0; i < pts.length; i++) {
            double s = f / (DIST - pts[i][2]);
            double x = cx + pts[i][0] * s, y = cy - pts[i][1] * s;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    static Color escurecer(Color c, double k) {
        return new Color(Math.min(255, (int) (c.getRed() * k)),
                Math.min(255, (int) (c.getGreen() * k)),
                Math.min(255, (int) (c.getBlue() * k)));
    }

    static double suave(double t) {           // ease in/out
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    // ------------------------------------------------------------------
    //  Álgebra linear básica
    // ------------------------------------------------------------------
    static int[][] identidade() { return new int[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}; }

    /** Matriz de rotação de 90° (dir = ±1) em torno do eixo 0=X, 1=Y, 2=Z. */
    static int[][] quartoDeVolta(int eixo, int dir) {
        int s = dir;
        switch (eixo) {
            case 0:  return new int[][]{{1, 0, 0}, {0, 0, -s}, {0, s, 0}};
            case 1:  return new int[][]{{0, 0, s}, {0, 1, 0}, {-s, 0, 0}};
            default: return new int[][]{{0, -s, 0}, {s, 0, 0}, {0, 0, 1}};
        }
    }

    static double[][] rotD(int eixo, double ang) {
        double c = Math.cos(ang), s = Math.sin(ang);
        switch (eixo) {
            case 0:  return new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
            case 1:  return new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
            default: return new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
        }
    }

    static int[][] mul(int[][] a, int[][] b) {
        int[][] r = new int[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }

    static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }

    static int[] mulV(int[][] m, int[] v) {
        return new int[]{
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    static double[] mulD(double[][] m, double[] v) {
        return new double[]{
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    static double[] mulD(int[][] m, double[] v) {
        return new double[]{
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    static double[] mulD(int[][] m, int[] v) {
        return mulD(m, new double[]{v[0], v[1], v[2]});
    }

    static double[] normalizar(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{v[0] / n, v[1] / n, v[2] / n};
    }

    // ------------------------------------------------------------------
    //  Janela e controles
    // ------------------------------------------------------------------
    static JButton botao(String texto, String dica, ActionListener acao) {
        JButton b = new JButton(texto);
        b.setToolTipText(dica);
        b.setFocusable(false);
        b.setMargin(new Insets(4, 10, 4, 10));
        b.addActionListener(acao);
        return b;
    }

    static void ligarTecla(CuboMagico3D p, int tecla, String nome, Runnable acao) {
        p.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(tecla, 0), nome);
        p.getActionMap().put(nome, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { acao.run(); }
        });
    }

    static void criarJanela() {
        JFrame janela = new JFrame("Simulador de Cubo Mágico 3D");
        CuboMagico3D painel = new CuboMagico3D();
        painel.setPreferredSize(new Dimension(960, 660));

        JPanel controles = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        controles.add(botao("Embaralhar", "Espaço", e -> painel.embaralhar()));
        controles.add(botao("Resolver", "Enter - desfaz todos os movimentos", e -> painel.resolver()));
        controles.add(botao("Reiniciar", "Esc - volta ao cubo resolvido", e -> painel.reiniciar()));
        controles.add(botao("Resetar vista", "Home", e -> painel.resetarVista()));
        controles.add(new JLabel("   Girar bloco:"));
        controles.add(botao("W ↑", "Girar para cima", e -> painel.girarSelecionado('W')));
        controles.add(botao("A ←", "Girar para a esquerda", e -> painel.girarSelecionado('A')));
        controles.add(botao("S ↓", "Girar para baixo", e -> painel.girarSelecionado('S')));
        controles.add(botao("D →", "Girar para a direita", e -> painel.girarSelecionado('D')));
        controles.add(new JLabel("   Velocidade:"));
        JSlider vel = new JSlider(1, 10, 6);
        vel.setFocusable(false);
        vel.setPreferredSize(new Dimension(120, 24));
        vel.addChangeListener(e -> painel.duracaoMs = 700 - (vel.getValue() - 1) * 65);
        controles.add(vel);

        // --- atalhos de teclado
        for (char l : "WASD".toCharArray())
            ligarTecla(painel, l, "girar" + l, () -> painel.girarSelecionado(l));
        ligarTecla(painel, KeyEvent.VK_SPACE, "embaralhar", painel::embaralhar);
        ligarTecla(painel, KeyEvent.VK_ENTER, "resolver", painel::resolver);
        ligarTecla(painel, KeyEvent.VK_ESCAPE, "reiniciar", painel::reiniciar);
        ligarTecla(painel, KeyEvent.VK_HOME, "vista", painel::resetarVista);
        ligarTecla(painel, KeyEvent.VK_LEFT, "esq", () -> { painel.yaw -= 0.12; painel.repaint(); });
        ligarTecla(painel, KeyEvent.VK_RIGHT, "dir", () -> { painel.yaw += 0.12; painel.repaint(); });
        ligarTecla(painel, KeyEvent.VK_UP, "cima", () -> {
            painel.pitch = Math.max(-1.55, painel.pitch - 0.12); painel.repaint(); });
        ligarTecla(painel, KeyEvent.VK_DOWN, "baixo", () -> {
            painel.pitch = Math.min(1.55, painel.pitch + 0.12); painel.repaint(); });

        janela.setLayout(new BorderLayout());
        janela.add(painel, BorderLayout.CENTER);
        janela.add(controles, BorderLayout.SOUTH);
        janela.pack();
        janela.setLocationRelativeTo(null);
        janela.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        janela.setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(CuboMagico3D::criarJanela);
    }
}

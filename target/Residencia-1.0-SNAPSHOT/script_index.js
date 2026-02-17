// 1. Pegando o elemento canvas do HTML
const canvas = document.getElementById('matrix');
const ctx = canvas.getContext('2d');

// 2. Variáveis de Configuração
const fontSize = 16;
const letters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ123456789@#$%^&*()*&^%';
const matrix = letters.split('');
let drops = [];
let columns = 0;

// [NOVO] Variável para memorizar a última largura conhecida
let lastWidth = window.innerWidth;

// --- FUNÇÃO DE INICIALIZAÇÃO (Roda uma vez no começo) ---
function initMatrix() {
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;

    columns = canvas.width / fontSize;

    drops = [];
    for (let x = 0; x < columns; x++) {
        drops[x] = 1;
    }
}

// --- FUNÇÃO INTELIGENTE DE REDIMENSIONAMENTO ---
function resizeCanvas() {
    const newWidth = window.innerWidth;
    const newHeight = window.innerHeight;

    // CENÁRIO 1: A largura mudou (Girou celular ou PC) -> RESET TOTAL
    if (newWidth !== lastWidth) {
        lastWidth = newWidth;
        initMatrix(); // Recria tudo do zero
    }
    // CENÁRIO 2: Só a altura mudou (Scroll do navegador mobile) -> AJUSTE SUAVE
    else {
        // Apenas atualiza a altura para cobrir a tela toda (caso a barra suma)
        canvas.height = newHeight;

        // O PULO DO GATO: NÃO zeramos o array 'drops' aqui!
        // A chuva continua caindo normalmente, apenas o "papel" ficou maior.
    }
}

// Chama a inicialização uma vez
initMatrix();

// "Ouvinte": Agora chama a função inteligente
window.addEventListener('resize', resizeCanvas);

// --- A ANIMAÇÃO (DRAW) ---
function draw() {
    // 1. Fundo com rastro (translucido)
    ctx.fillStyle = 'rgba(0, 0, 0, 0.05)';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    // 2. Cor e fonte
    ctx.fillStyle = '#0F0'; // Verde Hacker
    ctx.font = fontSize + 'px monospace';

    // 3. Loop das gotas
    for (let i = 0; i < drops.length; i++) {
        const text = matrix[Math.floor(Math.random() * matrix.length)];

        ctx.fillText(text, i * fontSize, drops[i] * fontSize);

        // --- RESET DA GOTA ---
        if (drops[i] * fontSize > canvas.height && Math.random() > 0.975) {
            drops[i] = 0;
        }

        drops[i]++;
    }
}

// Roda a animação
setInterval(draw, 45);

/* ==========================================================================
 FUNÇÃO DO CARROSSEL
 ========================================================================== */
function scrollCarousel(direction) {
    const container = document.getElementById('blocksContainer');

    // 1. Pega o primeiro bloco para medir o tamanho real dele agora
    const firstBlock = container.querySelector('.block-item');

    if (firstBlock) {
        // Largura do bloco + o gap (10px)
        const itemWidth = firstBlock.offsetWidth + 10;

        // Rola exatamente a largura de 1 item por vez
        container.scrollBy({
            left: direction * itemWidth,
            behavior: 'smooth'
        });
    }
}

/* ==========================================================================
 FUNÇÃO PARA ROLAR E CENTRALIZAR (SUAVE)
 ========================================================================== */
function scrollToCenter(selector) {
    const element = document.querySelector(selector);

    if (element) {
        // O comando mágico: rola suavemente e põe o bloco no CENTRO vertical
        element.scrollIntoView({
            behavior: 'smooth',
            block: 'center',
            inline: 'center'
        });
    }
}

async function performLogin(event) {
    event.preventDefault(); // Impede o recarregamento padrão do form

    // Busca o formulário e os campos
    // IMPORTANTE: Garanta que seu form no HTML tem onsubmit="performLogin(event)"
    const form = event.target;

    // Tenta pegar os inputs. Adapte os seletores se necessário.
    // Exemplo: no seu HTML está <input type="email"> sem ID, então pegamos pelo tipo
    const emailInput = form.querySelector('input[type="email"]');
    const passInput = form.querySelector('input[type="password"]');
    const btn = form.querySelector('button');

    const email = emailInput.value;
    const password = passInput.value;
    const txtOriginal = btn.innerText;

    // Efeito visual "Matrix"
    btn.innerText = "Decodificando...";
    btn.disabled = true;
    btn.style.opacity = "0.7";

    try {
        // Envia para o Backend
        const response = await fetch('./api/login', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({email: email, password: password})
        });

        const data = await response.json();

        if (data.success) {
            // === SUCESSO: ACESSO AO MAINFRAME ===
            console.log("Login autorizado:", data.user);

            // 1. Salva o Token Completo (Com nome da casa e código)
            localStorage.setItem('user_token', JSON.stringify(data.user));

            // 2. Redireciona para a pasta do Dashboard
            window.location.href = "dashboard/";

        } else if (data.requireReactivation) {

            // === PROTOCOLO DE RESSURREIÇÃO (MODO FANTASMA DETECTADO) ===
            // Usamos o confirm padrão para poder capturar a resposta (Sim/Não) do usuário
            const querVoltar = confirm("ALERTA: Sua conta encontra-se no MODO FANTASMA (Desativada).\n\nDeseja restabelecer sua conexão com a Matrix e recuperar seu perfil na residência?");

            if (querVoltar) {
                btn.innerText = "Ressuscitando...";

                // Dispara o comando de reativar para o Java
                const reactivateResponse = await fetch('./api/login', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({
                        action: 'REACTIVATE',
                        user_id: data.user_id
                    })
                });

                const reactData = await reactivateResponse.json();

                if (reactData.success) {
                    // Avisa que deu certo usando o seu modal customizado
                    showMatrixAlert("SISTEMA RESTABELECIDO", "Bem-vindo de volta! Faça seu login normalmente para sincronizar os dados.");
                    passInput.value = "";
                    passInput.focus();
                } else {
                    showMatrixAlert("FALHA DE RESSURREIÇÃO", reactData.message);
                }
            } else {
                // Usuário cancelou a volta
                passInput.value = "";
                passInput.focus();
            }

        } else {
            // === ERRO: ACESSO NEGADO ===
            // Usa seu modal novo em vez de alert
            showMatrixAlert("ACESSO NEGADO", data.message);

            // Limpa senha e foca nela
            passInput.value = "";
            passInput.focus();
        }

    } catch (error) {
        console.error("Erro de conexão:", error);
        showMatrixAlert("ERRO DE CONEXÃO", "O servidor Matrix não está respondendo.");
    } finally {
        // Restaura o botão
        btn.innerText = txtOriginal;
        btn.disabled = false;
        btn.style.opacity = "1";
    }
}

/* ==========================================================================
 MELHORIA DE UX: NAVEGAÇÃO COM A TECLA 'ENTER'
 ========================================================================== */

/* ==========================================================================
 MELHORIA DE UX: NAVEGAÇÃO SUPREMA COM A TECLA 'ENTER'
 ========================================================================== */

document.addEventListener('DOMContentLoaded', () => {

    // --- 1. ESCUDO NO FORMULÁRIO DE CADASTRO ---
    const formCadastro = document.getElementById('multi-step-form');

    if (formCadastro) {
        formCadastro.addEventListener('keydown', function (event) {
            // Se apertou Enter (e não estava segurando Shift)
            if (event.key === 'Enter') {
                event.preventDefault(); // PROÍBE o navegador de clicar nos botões sozinho

                // Descobre qual campo está piscando o cursor agora
                const campoAtivo = document.activeElement;

                // Lista de campos do Passo 1 na ordem correta
                const inputsPasso1 = [
                    document.getElementById('reg-name'),
                    document.getElementById('reg-email'),
                    document.getElementById('reg-pass'),
                    document.getElementById('reg-confirm')
                ];

                // Verifica se estamos no Passo 1
                const index = inputsPasso1.indexOf(campoAtivo);

                if (index !== -1) {
                    // Se não for o último campo, pula pro próximo
                    if (index < inputsPasso1.length - 1) {
                        inputsPasso1[index + 1].focus();
                    }
                    // Se for o último campo (Confirmar Senha), tenta avançar de tela
                    else {
                        irParaPasso2();
                    }
                }
                // Verifica se estamos no Passo 2 (Campo da Casa)
                else if (campoAtivo.id === 'house-data-input') {
                    // Aciona o envio final para o servidor
                    document.querySelector('#step-2 button[type="submit"]').click();
                }
            }
        });
    }

    // --- 2. ESCUDO NO FORMULÁRIO DE LOGIN ---
    const formLogin = document.querySelector('#form-login form');

    if (formLogin) {
        formLogin.addEventListener('keydown', function (event) {
            if (event.key === 'Enter') {
                event.preventDefault(); // Trava o envio automático

                const campoAtivo = document.activeElement;
                const inputs = Array.from(this.querySelectorAll('input'));
                const index = inputs.indexOf(campoAtivo);

                if (index !== -1) {
                    if (index < inputs.length - 1) {
                        inputs[index + 1].focus(); // Pula do Email pra Senha
                    } else {
                        // Da senha, clica no botão "Entrar"
                        this.querySelector('button[type="submit"]').click();
                    }
                }
            }
        });
    }

});

/* --- Função Especial para o Botão "Cadastrar" do Menu --- */
function irParaCadastro() {
    // 1. Pega os formulários
    const loginForm = document.getElementById('form-login');
    const registerForm = document.getElementById('form-register');

    // 2. Esconde o Login e Mostra o Cadastro (Forçado)
    if (loginForm && registerForm) {
        loginForm.classList.add('hidden');      // Esconde Login
        registerForm.classList.remove('hidden'); // Mostra Cadastro
    }

    // 3. Usa sua função para rolar suavemente até lá
    scrollToCenter('.right-content');
}

/* --- Função para o Botão "Home" --- */
function irParaHome() {
    const loginForm = document.getElementById('form-login');
    const registerForm = document.getElementById('form-register');
    if (loginForm && registerForm) {
        registerForm.classList.add('hidden');       // Esconde Cadastro
        loginForm.classList.remove('hidden');      // Mostra Login
    }

    scrollToCenter('.left-content');
}

function irParaSobre() {
    // 1. Esconde a Home e o Header (opcional, se quiser foco total)
    document.getElementById('home').style.display = 'none';

    // 2. Pega a seção Sobre
    const sobreSection = document.getElementById('sobre');

    // 3. Remove a classe que esconde e força o display flex
    sobreSection.classList.remove('hidden');
    sobreSection.style.display = 'flex';
    sobreSection.scrollTop = 0;
}

function voltarParaHome() {
    // 1. Esconde o Sobre
    const sobreSection = document.getElementById('sobre');
    sobreSection.classList.add('hidden');
    sobreSection.style.display = 'none';

    // 2. Mostra a Home novamente
    document.getElementById('home').style.display = 'flex';
}

/* ==========================================================================
 SISTEMA DE TOGGLE (LOGIN <-> CADASTRO) COM ROLAGEM
 ========================================================================== */
function toggleForms() {
    const loginForm = document.getElementById('form-login');
    const registerForm = document.getElementById('form-register');
    const container = document.querySelector('.right-content'); // O painel de vidro

    // 1. Troca os formulários
    if (loginForm.classList.contains('hidden')) {
        loginForm.classList.remove('hidden');
        registerForm.classList.add('hidden');
    } else {
        loginForm.classList.add('hidden');
        registerForm.classList.remove('hidden');
    }

    // 2. Aguarda um milissegundo para o navegador redesenhar o tamanho e centraliza
    setTimeout(() => {
        container.scrollIntoView({
            behavior: 'smooth',
            block: 'center'
        });
    }, 100);
}

/* ==========================================================================
 WIZARD DE CADASTRO (Passo a Passo)
 ========================================================================== */

// 1. Validação Local (Cliente) e Avanço
function irParaPasso2() {
    // Pega valores
    const nome = document.getElementById('reg-name').value.trim();
    const email = document.getElementById('reg-email').value.trim();
    const pass = document.getElementById('reg-pass').value;
    const conf = document.getElementById('reg-confirm').value;

    // Validações simples (sem ir no servidor)
    if (nome.length < 3) {
        alert("O nome precisa ter pelo menos 3 letras.");
        return;
    }
    if (!email.includes('@')) {
        alert("Email inválido.");
        return;
    }
    if (pass.length < 4) {
        alert("A senha é muito curta.");
        return;
    }
    if (pass !== conf) {
        alert("As senhas não conferem.");
        return;
    }

    // Se tudo ok, avança visualmente
    document.getElementById('step-1').classList.add('hidden');
    document.getElementById('step-2').classList.remove('hidden');
    const indicator = document.getElementById('step-indicator');
    if (indicator)
        indicator.innerText = "Passo 2/2";
}

// 2. Navegação: Voltar para o Passo 1
function voltarParaPasso1() {
    document.getElementById('step-2').classList.add('hidden');
    document.getElementById('step-1').classList.remove('hidden');
    document.getElementById('step-indicator').innerText = "Passo 1/2";
}

// 3. Lógica visual: Criar vs Entrar
function mudarModoCasa(modo) {
    const input = document.getElementById('house-data-input');
    const hiddenField = document.getElementById('house-action-type');
    const btnCreate = document.getElementById('btn-opt-create');
    const btnJoin = document.getElementById('btn-opt-join');

    hiddenField.value = modo;

    if (modo === 'CREATE') {
        input.placeholder = "Nome da República (Ex: Zion)";
        btnCreate.classList.add('active');
        btnJoin.classList.remove('active');
    } else {
        input.placeholder = "Código do Convite (Ex: #1234)";
        btnJoin.classList.add('active');
        btnCreate.classList.remove('active');
    }
    input.value = ""; // Limpa o campo ao trocar
    input.focus();
}

async function finalizarCadastro(event) {
    event.preventDefault();
    const btn = event.target.querySelector('button[type="submit"]');
    const txtOriginal = btn.innerText;

    btn.innerText = "Analisando...";
    btn.disabled = true;

    const payload = {
        name: document.getElementById('reg-name').value,
        email: document.getElementById('reg-email').value,
        password: document.getElementById('reg-pass').value,
        houseAction: document.getElementById('house-action-type').value,
        houseData: document.getElementById('house-data-input').value.trim()
    };

    // Função interna para processar o registro final
    const processarRegistro = async (finalPayload) => {
        try {
            const response = await fetch('./api/register', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(finalPayload)
            });
            const data = await response.json();

            if (data.success) {
                // SUCESSO! Mostra Modal Bonito
                localStorage.setItem('user_token', JSON.stringify(data.user));

                let invite = data.user.invite_code ? data.user.invite_code : null;
                showMatrixAlert("BEM-VINDO À MATRIX", `Operador ${data.user.name}, acesso concedido à residência ${data.user.house_name}.`, invite);

            } else {
                showMatrixAlert("ERRO NO SISTEMA", data.message);
            }
        } catch (error) {
            showMatrixAlert("FALHA CRÍTICA", "Erro de conexão com o servidor.");
        } finally {
            btn.innerText = txtOriginal;
            btn.disabled = false;
        }
    };

    // === INÍCIO DO FLUXO ===
    try {
        // 1. Verifica se a casa existe (apenas se for CREATE)
        if (payload.houseAction === 'CREATE') {
            const checkResp = await fetch('./api/house', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({action: 'CHECK_NAME', houseName: payload.houseData})
            });
            const checkResult = await checkResp.json();

            if (checkResult.exists) {
                // AQUI ESTÁ A CORREÇÃO PRINCIPAL:
                // Adicionamos a função de CANCELAMENTO (segundo argumento)
                showMatrixPrompt(
                        payload.houseData,
                        (codigoDigitado) => { // Confirmou
                    payload.houseAction = 'JOIN';
                    payload.houseData = codigoDigitado;
                    processarRegistro(payload);
                },
                        () => { // Cancelou
                    btn.innerText = txtOriginal;
                    btn.disabled = false;
                }
                );
                return;
            }
        }
        processarRegistro(payload);

    } catch (error) {
        console.error(error);
        showMatrixAlert("ERRO", error.message);
        btn.innerText = txtOriginal;
        btn.disabled = false;
    }
}

let callbackConfirmacaoCodigo = null;
let callbackCancelamento = null;



// 1. Função que mostra Alerta (Sucesso/Erro) -> CORRIGE O "showMatrixAlert is not defined"
function showMatrixAlert(title, message, inviteCode = null) {
    const overlay = document.getElementById('matrix-overlay');
    const msgModal = document.getElementById('modal-message');
    const inputModal = document.getElementById('modal-input');

    // Garante que os elementos existem antes de tentar usar
    if (!overlay || !msgModal) {
        alert(title + "\n" + message); // Fallback se o HTML não estiver pronto
        return;
    }

    overlay.classList.remove('hidden');
    msgModal.classList.remove('hidden');
    inputModal.classList.add('hidden');

    document.getElementById('msg-title').innerText = title;
    document.getElementById('msg-text').innerText = message;

    const codeBox = document.getElementById('msg-code-display');
    if (inviteCode) {
        codeBox.innerText = inviteCode;
        codeBox.classList.remove('hidden');
    } else {
        codeBox.classList.add('hidden');
}
}

// Abre o prompt pedindo código
// Agora aceita uma função de "onCancel" também
function showMatrixPrompt(houseName, onConfirm, onCancel) {
    document.getElementById('matrix-overlay').classList.remove('hidden');
    document.getElementById('modal-message').classList.add('hidden');
    document.getElementById('modal-input').classList.remove('hidden');

    document.getElementById('conflict-house-name').innerText = houseName;
    document.getElementById('matrix-input-code').value = "";
    document.getElementById('matrix-input-code').focus();

    // Guarda as funções para usar depois
    callbackConfirmacaoCodigo = onConfirm;
    callbackCancelamento = onCancel;
}

// Botão [ ABORT ] (Cancelar)
function cancelarEntrada() {
    document.getElementById('matrix-overlay').classList.add('hidden');

    // Se existir uma função de cancelamento configurada, executa ela
    if (callbackCancelamento) {
        callbackCancelamento();
    }
}

// Botão [ PROCEED ] (Confirmar)
function confirmarEntrada() {
    const code = document.getElementById('matrix-input-code').value;
    if (code && callbackConfirmacaoCodigo) {
        // Esconde modal
        document.getElementById('matrix-overlay').classList.add('hidden');
        // Executa a lógica de cadastro
        callbackConfirmacaoCodigo(code);
    } else {
        alert("Digite o código para prosseguir.");
    }
}

// Fecha modal de mensagem
function fecharMatrixModal() {
    document.getElementById('matrix-overlay').classList.add('hidden');
    const title = document.getElementById('msg-title').innerText;
    // Se for mensagem de sucesso, recarrega a página
    if (title.includes("BEM-VINDO") || title.includes("SUCESSO")) {
        window.location.href = "dashboard/";
    }
}







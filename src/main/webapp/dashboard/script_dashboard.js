/* ==========================================================================
 LÓGICA DO DASHBOARD (SECURE AREA)
 ========================================================================== */

// 1. GUARDA DE SEGURANÇA (Roda assim que abre o arquivo)
const userToken = localStorage.getItem('user_token'); // Mantive o nome padrão que estávamos usando

if (!userToken) {
    // Se não tem token, expulsa para o login
    alert("Acesso Negado. Identifique-se.");
    window.location.href = "../index.html";
}

let user = JSON.parse(userToken);

let todasTransacoes = []; // Guarda todos os dados originalmente vindos do banco
let todosMembros = [];
let lastDataHash = "";
let syncInterval;

// 2. INICIALIZAÇÃO
document.addEventListener("DOMContentLoaded", () => {
    carregarDadosUsuario();

    if (window.innerWidth <= 768) {
        document.querySelector('.sidebar').classList.add('collapsed');
    }

    iniciarComLink();
});

function carregarDadosUsuario() {
    // Preenche Sidebar
    const userNameEl = document.getElementById('dash-user-name');
    const userInitialEl = document.getElementById('dash-user-initial');
    const userRoleEl = document.getElementById('dash-user-role');

    if (userNameEl)
        userNameEl.innerText = user.name.split(' ')[0];
    if (userInitialEl)
        userInitialEl.innerText = user.name.charAt(0).toUpperCase();
    if (userRoleEl)
        userRoleEl.innerText = user.role === 'ADMIN' ? 'Operador Master' : 'Membro';

    // Preenche Header
    const houseNameEl = document.getElementById('dash-house-name');
    const inviteCodeEl = document.getElementById('dash-invite-code');
    const inviteBoxEl = document.querySelector('.invite-box');

    if (houseNameEl)
        houseNameEl.innerText = user.house_name || "Sem Teto";

    if (inviteCodeEl && inviteBoxEl) {
        if (user.role === 'ADMIN' && user.invite_code) {
            // É Admin: Mostra o código e libera a caixa
            inviteCodeEl.innerText = user.invite_code;
            inviteBoxEl.classList.remove('restricted');
        } else {
            // É Membro: Escreve Restrito e bloqueia a caixa
            inviteCodeEl.innerText = "Restrito";
            inviteBoxEl.classList.add('restricted');
        }
    }

    // Carrega tudo na primeira vez
    carregarTransacoes();

    // ==========================================
    // O MOTOR DO TEMPO REAL (FALTAVA ISSO!)
    // ==========================================
    if (syncInterval)
        clearInterval(syncInterval);

    syncInterval = setInterval(() => {
        carregarTransacoes(true); // "true" significa busca silenciosa (não trava a tela)
    }, 5000); // Bipa o servidor a cada 5 segundos
}

// 3. NAVEGAÇÃO
function loadModule(moduleName) {
    document.querySelectorAll('.nav-item').forEach(btn => btn.classList.remove('active'));

    document.getElementById('module-home').classList.add('hidden');
    document.getElementById('module-wallet').classList.add('hidden');
    document.getElementById('module-tasks').classList.add('hidden');
    document.getElementById('module-profile').classList.add('hidden');

    if (moduleName === 'HOME') {
        document.getElementById('module-home').classList.remove('hidden');
    } else if (moduleName === 'WALLET') {
        document.getElementById('module-wallet').classList.remove('hidden');
        carregarTransacoes();
    } else if (moduleName === 'PROFILE') {
        document.getElementById('module-profile').classList.remove('hidden');
        carregarPerfil();
    } else if (moduleName === 'TASKS') {
        document.getElementById('module-tasks').classList.remove('hidden');
    }

    // Se estiver num ecrã de telemóvel, fecha o menu automaticamente após clicar
    if (window.innerWidth <= 768) {
        document.querySelector('.sidebar').classList.add('collapsed');
    }
}

// 4. LOGOUT
function logoutSistema() {
    if (confirm("Desconectar da Matrix?")) {
        localStorage.removeItem('matrix_user');
        window.location.href = "../index.html";
    }
}

// 5. UTILITÁRIOS
function copiarCodigo() {
    // 1. Trava de Segurança: Se não for ADMIN, a função aborta aqui mesmo.
    if (user.role !== 'ADMIN')
        return;

    const code = document.getElementById('dash-invite-code').innerText;
    if (code !== "Restrito" && code !== "...") {
        navigator.clipboard.writeText(code);
        alert("Código de Acesso copiado: " + code);
    }
}

/* ==========================================================================
 LÓGICA DO MÓDULO FINANCEIRO (UI)
 ========================================================================== */

function abrirModalTransacao() {
    document.getElementById('form-transaction').reset();
    document.getElementById('trans-id').value = ""; // Limpa o ID
    document.getElementById('trans-modal-title').innerText = "REGISTRAR DESPESA";

    // Desbloqueia os campos de valor e rateio
    document.getElementById('trans-amount').disabled = false;
    document.getElementById('trans-shared').disabled = false;

    itensNotaAtual = [];

    document.getElementById('btn-revisar-itens').style.display = 'none';

    const sefazArea = document.getElementById('sefaz-link-area');
    if (sefazArea)
        sefazArea.style.display = 'none';

    document.getElementById('modal-transaction').classList.remove('hidden');
    document.getElementById('trans-desc').focus();
}

// Abre para EDITAR EXISTENTE
function abrirModalEdicao(transId) {
    // Busca os dados da conta salva na memória do JS
    const t = todasTransacoes.find(x => x.id === transId);
    if (!t)
        return;

    document.getElementById('form-transaction').reset();
    document.getElementById('trans-id').value = t.id; // Salva o ID no campo oculto
    document.getElementById('trans-modal-title').innerText = "EDITAR REGISTRO";

    // Preenche os campos
    document.getElementById('trans-desc').value = t.description;
    document.getElementById('trans-amount').value = t.amount;
    document.getElementById('trans-nf').value = t.nf_key || "";
    document.getElementById('trans-obs').value = t.observation || "";
    document.getElementById('trans-status').value = t.status;

    // Se tiver data, preenche
    if (t.due_date)
        document.getElementById('trans-due-date').value = t.due_date;

    // Bloqueia Valor e Rateio (Para não quebrar a matemática da casa)
    document.getElementById('trans-amount').disabled = true;
    const selectShared = document.getElementById('trans-shared');
    if (selectShared)
        selectShared.disabled = true;

    const sefazArea = document.getElementById('sefaz-link-area');
    if (sefazArea)
        sefazArea.style.display = 'none';

    document.getElementById('modal-transaction').classList.remove('hidden');
}

function fecharModalTransacao() {
    document.getElementById('modal-transaction').classList.add('hidden');
}

const formatarMoeda = (valor) => {
    return valor.toLocaleString('pt-BR', {style: 'currency', currency: 'BRL'});
};

/* ==========================================================================
 LÓGICA DE DESPESAS (CONECTADO AO BANCO COM FILTROS E SMART SYNC)
 ========================================================================== */

async function carregarTransacoes(isSilent = false) {
    if (!user.house_id)
        return;

    try {
        const response = await fetch(`../api/wallet?houseId=${user.house_id}`);
        const data = await response.json();

        if (data.success) {
            // === SMART SYNC ===
            const currentDataHash = JSON.stringify(data);
            if (currentDataHash === lastDataHash) {
                return; // Nada mudou. Aborta para não piscar a tela!
            }
            lastDataHash = currentDataHash;
            // ==================

            todasTransacoes = data.transactions || [];
            todosMembros = data.members || [];

            // 1. Atualiza WIDGET DA HOME
            const homeGasto = document.getElementById('home-gasto-mensal');
            const homeStatus = document.getElementById('home-status');

            if (homeGasto)
                homeGasto.innerText = formatarMoeda(data.gasto_mensal);
            if (homeStatus) {
                if (data.pending > 0) {
                    homeStatus.innerHTML = `<span style="color:#ffaa00;">⚠️ Pendente na casa: ${formatarMoeda(data.pending)}</span>`;
                } else {
                    homeStatus.innerHTML = `<span style="color:#00ff00;">✔ Todas as contas em dia!</span>`;
                }
            }

            // 3. Atualiza UI
            aplicarFiltros();
            atualizarWidgetContas();

            const filtroTempoSelect = document.getElementById('filter-members-time');
            const filtroTempo = filtroTempoSelect ? filtroTempoSelect.value : 'MONTH';
            renderizarMembrosCasa(filtroTempo);

        } else if (!isSilent) {
            console.error("Erro da Matrix:", data.message);
        }
    } catch (error) {
        if (!isSilent)
            console.error("Erro ao carregar despesas:", error);
}
}

// Lógica que lê os filtros e processa a lista
// Lógica que lê os filtros e processa a lista
function aplicarFiltros() {
    const searchEl = document.getElementById('filter-search');
    const operatorEl = document.getElementById('filter-operator');
    const statusEl = document.getElementById('filter-status');
    const dateSortEl = document.getElementById('filter-date');

    // Se os filtros não existirem na tela, aborta pra não dar erro
    if (!searchEl || !operatorEl || !statusEl || !dateSortEl)
        return;

    const search = searchEl.value.toLowerCase();
    const operator = operatorEl.value;
    const status = statusEl.value;
    const dateSort = dateSortEl.value;

    let filtradas = [...todasTransacoes];

    // 1. Filtro de Operador
    if (operator === 'ME') {
        filtradas = filtradas.filter(t => t.user_id === user.id);
    }

    // 2. Filtro de Status (COM LÓGICA DE ATRASADAS)
    const hoje = new Date();
    hoje.setHours(0, 0, 0, 0);

    if (status === 'OVERDUE') {
        // Pega só as Pendentes e compara a data igual fazemos na tabela
        filtradas = filtradas.filter(t => {
            if (t.status !== 'PENDING' || !t.due_date)
                return false;

            const parts = t.due_date.split('-');
            const vData = new Date(parts[0], parts[1] - 1, parts[2]);

            // Retorna TRUE se a data de vencimento for menor que hoje
            return vData < hoje;
        });
    } else if (status !== 'ALL') {
        // Se for Pago, Cancelado ou Pendente Normal
        filtradas = filtradas.filter(t => t.status === status);
    }

    // 3. Filtro de Pesquisa de Texto
    if (search.trim() !== '') {
        filtradas = filtradas.filter(t =>
            t.description.toLowerCase().includes(search) ||
                    (t.nf_key && t.nf_key.toLowerCase().includes(search))
        );
    }

    // 4. Ordenação
    filtradas.sort((a, b) => {
        // Ordenação por Registo (Data de Criação baseada no ID)
        if (dateSort === 'ASC')
            return a.id - b.id;
        if (dateSort === 'DESC')
            return b.id - a.id;

        // Ordenação por Vencimento
        if (dateSort === 'DUE_ASC' || dateSort === 'DUE_DESC') {
            // Se ambas não têm vencimento, desempata pelo ID
            if (!a.due_date && !b.due_date)
                return b.id - a.id;

            // Joga as contas sem vencimento sempre para o final da lista
            if (!a.due_date)
                return 1;
            if (!b.due_date)
                return -1;

            // Extrai as datas de forma limpa (YYY-MM-DD)
            const dA = a.due_date.split(' ')[0];
            const dB = b.due_date.split(' ')[0];

            // Se vencem no mesmo dia, desempata pela mais recente
            if (dA === dB)
                return b.id - a.id;

            if (dateSort === 'DUE_ASC') {
                return dA < dB ? -1 : 1; // As que vencem primeiro (Mais próximas)
            } else {
                return dA > dB ? -1 : 1; // As que vencem por último (Mais distantes)
            }
        }
    });

    // ==========================================
    // CÁLCULO DOS PAINÉIS DE RESUMO NO TOPO
    // ==========================================
    let totalPagoFiltrado = 0;
    let totalPendenteFiltrado = 0;

    const hjMes = hoje.getMonth() + 1;
    const hjAno = hoje.getFullYear();
    const temFiltroAtivo = (operator !== 'ALL' || status !== 'ALL' || search.trim() !== '');

    filtradas.forEach(t => {
        if (t.status === 'PAID') {
            if (temFiltroAtivo) {
                totalPagoFiltrado += t.amount;
            } else {
                const [tDia, tMes, tAno] = t.date.split('/').map(Number);
                if (tMes === hjMes && tAno === hjAno) {
                    totalPagoFiltrado += t.amount;
                }
            }
        } else if (t.status === 'PENDING') {
            totalPendenteFiltrado += t.amount;
        }
    });

    // Atualiza o visual no HTML
    const walletBalance = document.getElementById('wallet-balance');
    const walletPending = document.getElementById('wallet-pending');

    if (walletBalance) {
        walletBalance.innerText = formatarMoeda(totalPagoFiltrado);
        const tituloPago = walletBalance.previousElementSibling;
        if (tituloPago) {
            tituloPago.innerText = temFiltroAtivo ? "Total Pago (Filtrado)" : "Gasto Mensal (Pagos)";
        }
    }

    if (walletPending) {
        walletPending.innerText = formatarMoeda(totalPendenteFiltrado);
        const tituloPendente = walletPending.previousElementSibling;
        if (tituloPendente) {
            if (status === 'OVERDUE') {
                tituloPendente.innerHTML = "<span style='color:#ff0000; font-weight:bold; text-shadow: 0 0 8px #ff0000;'>Dívida Atrasada ⚠️</span>";
            } else {
                tituloPendente.innerText = temFiltroAtivo ? "Pendentes (Filtrado)" : "Contas Pendentes";
            }
        }
    }

    // Manda a lista processada para desenhar a tabela
    renderizarTabela(filtradas);
}

// Função que pega a lista filtrada e escreve no HTML
function renderizarTabela(listaTransacoes) {
    const tbody = document.getElementById('transaction-tbody');
    if (!tbody)
        return;

    tbody.innerHTML = "";

    if (listaTransacoes.length === 0) {
        tbody.innerHTML = "<tr><td colspan='8' style='text-align:center;'>Nenhum registro encontrado com estes filtros.</td></tr>";
        return;
    }

    listaTransacoes.forEach(t => {
        let statusHtml = "";
        let classCor = "";

        if (t.status === 'PAID') {
            statusHtml = `<span style="color:#00ff00; font-weight:bold;">[ PAGO ]</span>`;
            classCor = "value-green";
        } else if (t.status === 'PENDING') {
            statusHtml = `<span style="color:#ffaa00; font-weight:bold;">[ PENDENTE ]</span>`;
            classCor = "value-red";
        } else {
            statusHtml = `<span style="color:#555; text-decoration: line-through;">[ CANCELADO ]</span>`;
            classCor = "";
        }

        let nfFormatada = t.nf_key ? `<span class="nf-key-cell">${t.nf_key}</span>` : '<span style="color:#333;">-</span>';

        let vencimentoHtml = '<span style="color:#333;">-</span>';
        if (t.due_date) {
            const dataLimpa = t.due_date.split(' ')[0];
            const parts = t.due_date.split('-');
            const vData = new Date(parseInt(parts[0]), parseInt(parts[1]) - 1, parseInt(parts[2]));
            const hoje = new Date();
            hoje.setHours(0, 0, 0, 0);

            const dataFormatada = `${parts[2]}/${parts[1]}/${parts[0]}`;

            if (t.status === 'PENDING') {
                if (vData < hoje) {
                    vencimentoHtml = `<span style="color:#ff0000; font-weight:bold; text-shadow: 0 0 5px #ff0000;" title="CONTA ATRASADA!">⚠️ ${dataFormatada}</span>`;
                } else if (vData.getTime() === hoje.getTime()) {
                    vencimentoHtml = `<span style="color:#ffaa00; font-weight:bold;" title="Vence hoje!">⚠️ HOJE</span>`;
                } else {
                    vencimentoHtml = `<span style="color:#ccc;">${dataFormatada}</span>`;
                }
            } else {
                vencimentoHtml = `<span style="color:#ccc;">${dataFormatada}</span>`;
            }
        }

        let acoesHtml = '<span style="color:#333;">-</span>';

        // Verifica se a conta pertence ao Operador Logado
        if (t.user_id === user.id) {
            let botoesExtras = "";
            // Só mostra "Pagar" e "Cancelar" se a conta estiver Pendente
            if (t.status === 'PENDING') {
                botoesExtras = `
                    <button class="btn-action-table btn-pay" onclick="mudarStatusConta(${t.id}, 'PAY')">Pagar</button>
                    <button class="btn-action-table btn-cancel-table" onclick="mudarStatusConta(${t.id}, 'CANCEL')">Cancelar</button>
                `;
            }

            // O botão "Editar" aparece sempre, mesmo se já estiver paga (para anexar NF, por exemplo)
            acoesHtml = `
                <div class="action-buttons-container">
                    <button class="btn-action-table btn-edit" onclick="abrirModalEdicao(${t.id})">Editar</button>
                    ${botoesExtras}
                </div>
            `;
        } else if (t.status === 'PENDING' && t.user_active === false) {
            // === NOVO: A conta é de um FANTASMA e está PENDENTE ===
            // Libera o botão "Quitar Dívida" na cor Ciano Neon
            acoesHtml = `
                <div class="action-buttons-container">
                    <button class="btn-action-table btn-pay" style="color: #00ffff; border-color: #00ffff;" title="Assumir e pagar dívida de morador inativo" onclick="mudarStatusConta(${t.id}, 'PAY')">
                        Quitar Dívida
                    </button>
                </div>
            `;

        } else {
            acoesHtml = `<span style="color:#555; font-size: 0.85em; font-weight: bold;">🔒 RESTRITO</span>`;
        }

        let estiloLinha = "";
        if (t.status === 'CANCELED') {
            estiloLinha = 'style="color: #555; text-decoration: line-through;"';
            nfFormatada = t.nf_key ? `<span class="nf-key-cell" style="text-decoration: line-through;">${t.nf_key}</span>` : '<span style="color:#333;">-</span>';
            classCor = "";
        }

        let obsHtml = "";
        if (t.observation && t.observation.trim() !== "") {
            obsHtml = `<br><span style="color: #666; font-size: 0.8em;">> ${t.observation}</span>`;
        }

        let nomeOperador = t.user_active ?
                t.user_name :
                `<span style="color:#555; text-decoration:line-through;" title="Usuário Desintegrado">${t.user_name}</span> 👽`;

        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td ${estiloLinha}>${t.date}</td>
            <td ${estiloLinha}>${nomeOperador}</td> 
            <td ${estiloLinha}>
                ${t.description} 
                ${obsHtml} 
            </td>
            <td class="${classCor}" ${estiloLinha}>${formatarMoeda(t.amount)}</td>
            <td ${estiloLinha}>${vencimentoHtml}</td> 
            <td ${estiloLinha}>${nfFormatada}</td>
            <td>${statusHtml}</td>
            <td>${acoesHtml}</td>
        `;
        tbody.appendChild(tr);
    });
}

async function salvarTransacao(event) {
    event.preventDefault();
    const btn = event.target.querySelector('button[type="submit"]');
    btn.innerText = "[ ENVIANDO... ]";
    btn.disabled = true;

    // === O PULO DO GATO: VERIFICA SE É CRIAÇÃO OU EDIÇÃO ===
    const idInput = document.getElementById('trans-id');
    const transId = idInput && idInput.value ? parseInt(idInput.value) : 0;
    const acaoAtual = transId > 0 ? 'EDIT' : 'CREATE';

    const desejaDividirEl = document.getElementById('trans-shared');
    const desejaDividir = desejaDividirEl ? (desejaDividirEl.value === 'true') : false;

    const payload = {
        action: acaoAtual, // <-- AGORA ELE AVISA O JAVA SE É EDIT
        transaction_id: transId, // <-- AGORA ELE MANDA O ID DA CONTA
        house_id: user.house_id,
        user_id: user.id,
        description: document.getElementById('trans-desc').value,
        amount: parseFloat(document.getElementById('trans-amount').value),
        nf_key: document.getElementById('trans-nf').value.replace(/\s/g, ''),
        status: document.getElementById('trans-status').value,
        isShared: desejaDividir,
        due_date: document.getElementById('trans-due-date') ? document.getElementById('trans-due-date').value : null,
        observation: document.getElementById('trans-obs') ? document.getElementById('trans-obs').value : "",
        items: itensNotaAtual.length > 0 ? itensNotaAtual : null
    };

    try {
        const response = await fetch('../api/wallet', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const data = await response.json();

        if (data.success) {
            fecharModalTransacao();
            document.getElementById('form-transaction').reset();

            itensNotaAtual = [];

            // Limpa o ID oculto para não travar o modal em "Modo Edição"
            if (idInput)
                idInput.value = "";

            // Recarrega na hora para a conta aparecer instantaneamente na sua tela!
            carregarTransacoes(true);

            // Avisos dinâmicos
            if (acaoAtual === 'CREATE' && desejaDividir) {
                alert("A conta foi fatiada automaticamente para todos os moradores vivos!");
            }
        } else {
            alert("Erro: " + data.message);
        }
    } catch (e) {
        alert("Falha crítica ao enviar para a Matrix.");
    } finally {
        btn.innerText = "[ REGISTRAR ]";
        btn.disabled = false;
    }
}

async function mudarStatusConta(transId, acao) {
    const msg = acao === 'PAY' ? "Confirmar o pagamento desta conta?" : "Deseja CANCELAR este registro? Ele não será mais contabilizado.";
    if (!confirm(msg))
        return;

    try {
        const response = await fetch('../api/wallet', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({action: acao, transaction_id: transId, house_id: user.house_id})
        });
        const data = await response.json();

        if (data.success) {
            carregarTransacoes(true); // Atualiza imediato
        } else {
            alert("Erro: " + data.message);
        }
    } catch (e) {
        alert("Erro de comunicação.");
    }
}

/* ==========================================================================
 LÓGICA DO PERFIL (MEUS DADOS)
 ========================================================================== */
async function carregarPerfil() {
    try {
        const response = await fetch(`../api/profile?userId=${user.id}`);
        const data = await response.json();

        if (data.success) {
            document.getElementById('prof-name').value = data.user.name;
            document.getElementById('prof-phone').value = data.user.phone;
            document.getElementById('prof-pix').value = data.user.pix_key;
        }
    } catch (e) {
        console.error("Erro ao carregar perfil.");
    }
}

async function salvarPerfil(event) {
    event.preventDefault();
    const btn = event.target.querySelector('button');
    btn.innerText = "[ ATUALIZANDO... ]";

    const payload = {
        action: 'UPDATE',
        user_id: user.id,
        name: document.getElementById('prof-name').value,
        phone: document.getElementById('prof-phone').value,
        pix_key: document.getElementById('prof-pix').value
    };

    try {
        const response = await fetch('../api/profile', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const data = await response.json();

        if (data.success) {
            alert("Dados salvos com sucesso!");

            // Atualiza a memória local (LocalStorage) com o novo nome
            user.name = payload.name;
            localStorage.setItem('matrix_user', JSON.stringify(user));

            document.getElementById('dash-user-name').innerText = payload.name.split(' ')[0];
            document.getElementById('dash-user-initial').innerText = payload.name.charAt(0).toUpperCase();
        } else {
            alert("Erro: " + data.message);
        }
    } catch (e) {
        alert("Falha de conexão.");
    } finally {
        btn.innerText = "[ ATUALIZAR REGISTROS ]";
    }
}

async function excluirConta() {
    // Dupla confirmação de segurança!
    if (!confirm("ATENÇÃO: Você está prestes a deletar sua conta permanentemente.\nTem certeza absoluta?"))
        return;
    if (!confirm("Último aviso. Suas contas cadastradas também serão apagadas. Deseja prosseguir?"))
        return;

    try {
        const payload = {
            action: 'DELETE',
            user_id: user.id,
            house_id: user.house_id,
            role: user.role
        };

        const response = await fetch('../api/profile', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const data = await response.json();

        if (data.success) {
            alert("Desconectado da Matrix. Adeus.");
            localStorage.removeItem('matrix_user'); // Apaga a memória local
            window.location.href = "../index.html"; // Joga pra fora do sistema
        } else {
            alert("Erro: " + data.message);
        }
    } catch (e) {
        alert("Falha de conexão com o servidor central.");
    }
}

/* ==========================================================================
 WIDGETS DA HOME
 ========================================================================== */

function renderizarMembrosCasa(filtroTempo) {
    const painelHtml = document.getElementById('home-members-list');
    if (!painelHtml)
        return;

    painelHtml.innerHTML = "";

    if (!todosMembros || todosMembros.length === 0) {
        painelHtml.innerHTML = "<p style='color: #888;'>Sem registos de moradores na Matrix.</p>";
        return;
    }

    const hoje = new Date();
    const hjDia = hoje.getDate();
    const hjMes = hoje.getMonth() + 1;
    const hjAno = hoje.getFullYear();

    todosMembros.forEach(membro => {
        let totalContribuido = 0;

        let transacoesDoMembro = todasTransacoes.filter(t => t.user_id === membro.id && t.status === 'PAID');

        transacoesDoMembro.forEach(t => {
            const [tDia, tMes, tAno] = t.date.split('/').map(Number);
            let deveSomar = false;

            if (filtroTempo === 'ALL') {
                deveSomar = true;
            } else if (filtroTempo === 'YEAR') {
                if (tAno === hjAno)
                    deveSomar = true;
            } else if (filtroTempo === 'MONTH') {
                if (tMes === hjMes && tAno === hjAno)
                    deveSomar = true;
            } else if (filtroTempo === 'DAY') {
                if (tDia === hjDia && tMes === hjMes && tAno === hjAno)
                    deveSomar = true;
            }

            if (deveSomar) {
                totalContribuido += t.amount;
            }
        });

        const telefone = membro.phone !== "" ? membro.phone : "Não registado";
        const pix = membro.pix_key !== "" ? membro.pix_key : "Não registado";

        const estiloCard = membro.active ? "" : "opacity: 0.4; filter: grayscale(100%); border-color: #333;";
        const tagStatus = membro.active ? "" : "<span style='color:#ff0000; font-size: 0.7em;'> [OFF]</span>";

        const div = document.createElement('div');
        div.className = 'member-card';
        div.style = estiloCard;
        div.innerHTML = `
            <div class="member-header">
                <span class="member-name">${membro.name.split(' ')[0]} ${tagStatus}</span>
                <span class="member-total" style="${totalContribuido > 0 ? 'color:#00ff00;' : 'color:#555;'}">
                    ${formatarMoeda(totalContribuido)}
                </span>
            </div>
            <div class="member-info">
                <span class="icon">📞</span> 
                <span title="Telefone">${telefone}</span>
            </div>
            <div class="member-info">
                <span class="icon">💠</span> 
                <span title="Chave PIX" style="word-break: break-all;">${pix}</span>
            </div>
        `;
        painelHtml.appendChild(div);
    });
}

function atualizarWidgetContas() {
    const listaHtml = document.getElementById('home-upcoming-bills');
    if (!listaHtml)
        return;

    let contasPendentes = todasTransacoes.filter(t => t.status === 'PENDING' && t.due_date);

    if (contasPendentes.length === 0) {
        listaHtml.innerHTML = `
            <li class="bill-item upcoming">
                <span class="bill-badge badge-green">[ ESTÁVEL ]</span>
                <span class="bill-title" style="color:#00ff00;">Nenhuma anomalia financeira detectada.</span>
            </li>`;
        return;
    }

    contasPendentes.sort((a, b) => new Date(a.due_date) - new Date(b.due_date));
    listaHtml.innerHTML = "";

    contasPendentes.slice(0, 5).forEach(t => {
        const parts = t.due_date.split('-');
        const vData = new Date(parts[0], parts[1] - 1, parts[2]);
        const hoje = new Date();
        hoje.setHours(0, 0, 0, 0);

        let classeItem = "upcoming";
        let classeBadge = "badge-green";
        let classeValor = "green";
        let textoAlerta = `${parts[2]}/${parts[1]}`;

        if (vData < hoje) {
            classeItem = "overdue";
            classeBadge = "badge-red";
            classeValor = "red";
            textoAlerta = "ATRASADA!";
        } else if (vData.getTime() === hoje.getTime()) {
            classeItem = "today";
            classeBadge = "badge-yellow";
            classeValor = "yellow";
            textoAlerta = "HOJE!";
        }

        const li = document.createElement('li');
        li.className = `bill-item ${classeItem}`;

        li.innerHTML = `
            <span class="bill-badge ${classeBadge}">[ ${textoAlerta} ]</span> 
            <span class="bill-title">${t.description}</span> 
            <span class="bill-amount ${classeValor}">${formatarMoeda(t.amount)}</span>
        `;
        listaHtml.appendChild(li);
    });
}

// 6. TOGGLE MENU (ABRIR/FECHAR SIDEBAR)
function toggleNav() {
    const sidebar = document.querySelector('.sidebar');
    const iconArrow = document.getElementById('divider-icon-arrow');

    if (sidebar) {
        sidebar.classList.toggle('collapsed');

        // Inverte a direção da setinha
        if (sidebar.classList.contains('collapsed')) {
            iconArrow.innerText = "▶"; // Menu fechado, seta pra fora
        } else {
            iconArrow.innerText = "◀"; // Menu aberto, seta pra dentro
        }
    }
}

/* ==========================================================================
 TERMINAL DE DESTRUIÇÃO (ZONA DE RISCO)
 ========================================================================== */

function abrirModalPerigo() {
    // Reseta o modal
    const input = document.getElementById('danger-confirm-text');
    input.value = "";

    // Trava o botão
    validarExclusao();

    // Abre a tela
    document.getElementById('modal-danger').classList.remove('hidden');
    input.focus();
}

function fecharModalPerigo() {
    document.getElementById('modal-danger').classList.add('hidden');
}

// Libera o botão apenas se o usuário digitar a palavra exata
function validarExclusao() {
    const input = document.getElementById('danger-confirm-text').value;
    const btn = document.getElementById('btn-confirm-delete');

    if (input === "DESINTEGRAR") {
        btn.disabled = false;
        btn.style.color = "#ff0000";
        btn.style.borderColor = "#ff0000";
        btn.style.cursor = "pointer";
        btn.style.textShadow = "0 0 8px #ff0000";
        btn.style.boxShadow = "inset 0 0 10px rgba(255,0,0,0.2)";
    } else {
        btn.disabled = true;
        btn.style.color = "#550000";
        btn.style.borderColor = "#550000";
        btn.style.cursor = "not-allowed";
        btn.style.textShadow = "none";
        btn.style.boxShadow = "none";
    }
}

// Executa a deleção real no Banco de Dados
async function executarExclusaoConta() {
    const btn = document.getElementById('btn-confirm-delete');
    btn.innerText = "[ APAGANDO REGISTROS... ]";
    btn.disabled = true;

    try {
        const payload = {
            action: 'DELETE',
            user_id: user.id,
            house_id: user.house_id,
            role: user.role
        };

        const response = await fetch('../api/profile', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const data = await response.json();

        if (data.success) {
            alert("Sinal perdido. Você foi desconectado da Matrix.");
            localStorage.removeItem('matrix_user'); // Apaga a memória
            window.location.href = "../index.html"; // Chuta pro Login
        } else {
            alert("Erro: " + data.message);
            fecharModalPerigo();
        }
    } catch (e) {
        alert("Falha de conexão com o servidor central.");
        fecharModalPerigo();
    }
}

/* ==========================================================================
 SISTEMA DE CONFIRMAÇÃO (PAGAR / CANCELAR) - GATILHO DIRETO
 ========================================================================== */

let transacaoAlvoId = null;
let transacaoAlvoAcao = null;

function mudarStatusConta(idTransacao, novaAcao) {
    transacaoAlvoId = idTransacao;
    transacaoAlvoAcao = novaAcao;

    const titulo = novaAcao === 'PAY' ? "AUTORIZAR PAGAMENTO" : "CANCELAR REGISTRO";
    const msg = novaAcao === 'PAY'
            ? "Deseja confirmar a quitação desta despesa? Esta ação atualizará o saldo mensal."
            : "Tem certeza que deseja cancelar esta conta? O valor será anulado da Matrix.";

    document.getElementById('confirm-title').innerText = titulo;
    document.getElementById('confirm-message').innerText = msg;
    document.getElementById('modal-confirm').classList.remove('hidden');
}

function fecharConfirmacao() {
    document.getElementById('modal-confirm').classList.add('hidden');
    transacaoAlvoId = null;
    transacaoAlvoAcao = null;
}

// Essa função precisa ser chamada pelo ONCLICK do botão no HTML
async function confirmarAcaoTransacao() {
    if (!transacaoAlvoId || !transacaoAlvoAcao)
        return;

    const btn = document.getElementById('btn-confirm-yes');
    btn.innerText = "[ PROCESSANDO... ]";
    btn.disabled = true;

    try {
        const payload = {
            action: transacaoAlvoAcao,
            transaction_id: transacaoAlvoId,
            house_id: user.house_id
        };

        const response = await fetch('../api/wallet', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });

        const data = await response.json();

        if (data.success) {
            fecharConfirmacao();
            carregarTransacoes(true); // Atualiza imediato a tela
        } else {
            alert("Erro: " + data.message);
            fecharConfirmacao();
        }
    } catch (e) {
        alert("Falha de comunicação com o servidor.");
        fecharConfirmacao();
    } finally {
        btn.innerText = "[ CONFIRMAR ]";
        btn.disabled = false;
    }
}

/* ==========================================================================
 SISTEMA DE CHAT P2P / ENCRIPTAÇÃO MATRIX / HISTÓRICO E ONLINE
 ========================================================================== */

let chatSocket = null;

function encriptarMatrix(texto) {
    return btoa(encodeURIComponent(texto));
}
function decriptarMatrix(hash) {
    try {
        return decodeURIComponent(atob(hash));
    } catch (e) {
        return hash;
    }
}

// === DESENHA A LISTA DE QUEM ESTÁ ONLINE ===
function atualizarUsuariosOnline(listaUsuarios) {
    const ul = document.getElementById('online-users-list');
    ul.innerHTML = ""; // Limpa a lista velha

    listaUsuarios.forEach(nome => {
        const li = document.createElement('li');
        li.style.marginBottom = "5px";

        // Destaca você de verde, os outros ficam em Ciano Matrix
        if (nome === user.name.split(' ')[0]) {
            li.style.color = "#00ff00";
            li.innerHTML = `● ${nome} (Você)`;
        } else {
            li.style.color = "#00ffff";
            li.innerHTML = `● ${nome}`;
        }
        ul.appendChild(li);
    });
}

// === CONTROLE DE LEITURA (NÍVEL 2) ===
let mensagensNaoLidas = 0;
let maiorIdMensagemRecebido = 0; // Guarda o ID da última mensagem que chegou na tela

function atualizarBadge() {
    const badge = document.getElementById('chat-badge');
    if (badge) {
        if (mensagensNaoLidas > 0) {
            badge.innerText = mensagensNaoLidas > 99 ? '99+' : mensagensNaoLidas;
            badge.style.display = 'inline-block';
        } else {
            badge.style.display = 'none';
        }
    }
}

function marcarComoLido() {
    // Só envia o aviso para o Java se tivermos mensagens e o socket estiver aberto
    if (maiorIdMensagemRecebido > 0 && chatSocket && chatSocket.readyState === WebSocket.OPEN) {
        chatSocket.send("MARK_READ:" + maiorIdMensagemRecebido);
    }
    mensagensNaoLidas = 0;
    atualizarBadge();
}

function toggleChat() {
    const sidebar = document.getElementById('chat-sidebar');
    if (sidebar) {
        sidebar.classList.toggle('open');

        if (sidebar.classList.contains('open')) {
            document.getElementById('chat-input').focus();

            marcarComoLido(); // Avisa o Banco de Dados que lemos tudo!

            const chatMessages = document.getElementById('chat-messages');
            if (chatMessages)
                chatMessages.scrollTop = chatMessages.scrollHeight;

            if (!chatSocket || chatSocket.readyState !== WebSocket.OPEN) {
                iniciarComLink();
            }
        }
    }
}

function enviarMensagemChat() {
    const input = document.getElementById('chat-input');
    const msgOriginal = input.value.trim();

    if (!msgOriginal || !chatSocket || chatSocket.readyState !== WebSocket.OPEN)
        return;

    chatSocket.send(encriptarMatrix(msgOriginal)); // Manda o Hash pro Java
    input.value = "";
}

function desenharMensagem(remetente, msgCriptografada) {
    const chatBox = document.getElementById('chat-messages');
    const msgLimpa = decriptarMatrix(msgCriptografada);
    const msgDiv = document.createElement('div');

    if (remetente === user.name.split(' ')[0]) {
        msgDiv.className = 'chat-msg me';
    } else {
        msgDiv.className = 'chat-msg';
    }

    msgDiv.innerHTML = `<span class="sender">${remetente}</span>${msgLimpa}`;
    chatBox.appendChild(msgDiv);
    chatBox.scrollTop = chatBox.scrollHeight;
}

function iniciarComLink() {
    if (chatSocket || !user || !user.house_id)
        return;

    let basePath = window.location.pathname.substring(0, window.location.pathname.lastIndexOf('/dashboard'));
    let wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';

    // MUDANÇA DE SEGURANÇA: Agora passamos user.id em vez do nome!
    let wsUrl = `${wsProtocol}//${window.location.host}${basePath}/api/chat/${user.house_id}/${user.id}`;

    chatSocket = new WebSocket(wsUrl);

    const inputChat = document.getElementById('chat-input');
    const btnChat = document.querySelector('.chat-input-area .btn-matrix');

    chatSocket.onopen = function () {
        console.log(">> COM-LINK ESTABELECIDO <<");
        const statusIcon = document.getElementById('chat-status-icon');
        const statusText = document.getElementById('chat-status-text');

        if (statusIcon && statusText) {
            statusIcon.style.color = '#00ff00';
            statusIcon.classList.add('blink');
            statusText.innerHTML = '&nbsp;ONLINE';
        }

        if (inputChat && btnChat) {
            inputChat.disabled = false;
            btnChat.disabled = false;
            inputChat.placeholder = "Transmitir mensagem...";
            inputChat.style.opacity = "1";
            btnChat.style.opacity = "1";
            btnChat.style.cursor = "pointer";
        }

        chatSocket.pingInterval = setInterval(() => {
            if (chatSocket && chatSocket.readyState === WebSocket.OPEN)
                chatSocket.send("SYS_PING");
        }, 5000);
    };

    chatSocket.onmessage = function (event) {
        const pacote = JSON.parse(event.data);
        const sidebar = document.getElementById('chat-sidebar');
        const isChatOpen = sidebar && sidebar.classList.contains('open');

        // Pega o seu próprio nome (exatamente como o Java processa)
        const meuNome = user.name.split(' ')[0];

        if (pacote.type === 'HISTORY') {
            const chatBox = document.getElementById('chat-messages');
            chatBox.innerHTML = '<div style="color: #666; font-size: 0.8em; text-align: center; margin-top: 10px; font-family: monospace;">>_ Recuperando banco de dados. Criptografia ativa.</div>';

            let lastReadId = pacote.lastReadId;
            let unreadCount = 0;
            let temMensagemMinhaNaoLida = false;

            pacote.messages.forEach(msg => {
                desenharMensagem(msg.sender, msg.message);

                if (msg.id > maiorIdMensagemRecebido)
                    maiorIdMensagemRecebido = msg.id;

                // === FILTRO DE ESPELHO NO HISTÓRICO ===
                const isMinhaMensagem = (msg.sender === meuNome);

                if (msg.id > lastReadId) {
                    if (!isMinhaMensagem) {
                        unreadCount++; // Só soma se a mensagem for de OUTRA pessoa
                    } else {
                        temMensagemMinhaNaoLida = true; // Se eu mandei de outra tela, preciso atualizar o banco
                    }
                }
            });

            if (!isChatOpen) {
                mensagensNaoLidas = unreadCount;
                atualizarBadge();
                // Se o histórico puxou uma mensagem minha que o banco achava que não estava lida, atualiza.
                if (temMensagemMinhaNaoLida)
                    marcarComoLido();
            } else {
                marcarComoLido();
            }

        } else if (pacote.type === 'MESSAGE') {
            desenharMensagem(pacote.sender, pacote.message);

            if (pacote.id > maiorIdMensagemRecebido)
                maiorIdMensagemRecebido = pacote.id;

            // === FILTRO DE ESPELHO EM TEMPO REAL ===
            const isMinhaMensagem = (pacote.sender === meuNome);

            if (!isChatOpen) {
                if (!isMinhaMensagem) {
                    // Só apita e soma se for mensagem da Leni (ou outro morador)
                    mensagensNaoLidas++;
                    atualizarBadge();
                } else {
                    // Fui eu que mandei do outro navegador! Atualiza o banco silenciosamente
                    marcarComoLido();
                }
            } else {
                marcarComoLido();
            }

        } else if (pacote.type === 'USERS') {
            atualizarUsuariosOnline(pacote.list);
        }
    };

    chatSocket.onclose = function () {
        console.log(">> COM-LINK OFFLINE. A TENTAR RECONECTAR... <<");
        const statusIcon = document.getElementById('chat-status-icon');
        const statusText = document.getElementById('chat-status-text');

        if (statusIcon && statusText) {
            statusIcon.style.color = '#ffaa00';
            statusIcon.classList.add('blink');
            statusText.innerHTML = '&nbsp;RECONECTANDO...';
        }

        if (inputChat && btnChat) {
            inputChat.disabled = true;
            btnChat.disabled = true;
            inputChat.placeholder = "[ SINAL PERDIDO - AGUARDANDO RECONEXÃO ]";
            inputChat.style.opacity = "0.5";
            btnChat.style.opacity = "0.5";
            btnChat.style.cursor = "not-allowed";
        }

        if (chatSocket && chatSocket.pingInterval)
            clearInterval(chatSocket.pingInterval);
        chatSocket = null;
        setTimeout(iniciarComLink, 5000);
    };
}

// NOTA FISCAL
// Dicionário IBGE -> SEFAZ
// ==========================================
// MÓDULO NFC-e / DIVISOR DE CONTAS
// ==========================================

const SEFAZ_URLS = {
    '11': 'http://www.nfce.sefin.ro.gov.br/', '12': 'http://www.sefaznet.ac.gov.br/nfce/consulta', '13': 'http://sistemas.sefaz.am.gov.br/nfceweb/formConsulta.do', '14': 'http://www.sefaz.rr.gov.br/nfce/consulta', '15': 'https://appnfc.sefa.pa.gov.br/portal/view/consultas/nfce/nfceForm.seam', '16': 'https://www.sefaz.ap.gov.br/sate/seg/SEGf_AcessarFuncao.jsp?cdFuncao=FIS_1261', '17': 'http://www.sefaz.to.gov.br/nfce/consulta.jsf', '21': 'http://www.nfce.sefaz.ma.gov.br/portal/consultarNFCe.jsp', '22': 'http://webas.sefaz.pi.gov.br/nfceweb/consultarNFCe.jsf', '23': 'http://nfce.sefaz.ce.gov.br/pages/ShowNFCe.html', '24': 'http://nfce.set.rn.gov.br/consultarNFCe.aspx', '25': 'http://www.receita.pb.gov.br/ser/servicos-nfce/consultar-nfce', '26': 'http://nfce.sefaz.pe.gov.br/nfce-web/consultarNFCe', '27': 'http://nfce.sefaz.al.gov.br/consultaNFCe.htm', '28': 'http://www.nfce.se.gov.br/portal/consultarNFCe.jsp', '29': 'http://nfe.sefaz.ba.gov.br/servicos/nfce/modulos/geral/NFCEC_consulta_chave_acesso.aspx', '31': 'http://nfce.fazenda.mg.gov.br/portalnfce', '32': 'http://app.sefaz.es.gov.br/ConsultaNFCe/ws/consultarNFCe.asmx', '33': 'http://www4.fazenda.rj.gov.br/consultaDFe/paginas/consultaChaveAcesso.faces', '35': 'https://www.nfce.fazenda.sp.gov.br/NFCeConsultaPublica/Paginas/ConsultaPublica.aspx', '41': 'http://www.fazenda.pr.gov.br/nfce/consulta', '42': 'https://sat.sef.sc.gov.br/tax.NET/sat.nfe.web/consulta_publica_nfce.aspx', '43': 'https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx', '50': 'http://www.dfe.ms.gov.br/nfce/consulta', '51': 'http://www.sefaz.mt.gov.br/nfce/consultanfce', '52': 'https://www.sefaz.go.gov.br/nfce/consulta', '53': 'http://dec.fazenda.df.gov.br/ConsultarNFCe.aspx'
};

let itensNotaAtual = [];

function buscarSefaz() {
    const inputChave = document.getElementById('trans-nf');
    const chave = inputChave.value.replace(/\D/g, '');

    if (chave.length !== 44) {
        alert("A chave de acesso deve conter exatamente 44 números.");
        return;
    }

    const ufCode = chave.substring(0, 2);
    const urlSefaz = SEFAZ_URLS[ufCode];

    if (urlSefaz) {
        // Envia para a raiz do site, sem forçar parâmetros na URL
        document.getElementById('btn-open-sefaz').href = urlSefaz;
        document.getElementById('sefaz-link-area').style.display = 'block';

        // Copia automaticamente a chave para facilitar o Ctrl+V
        navigator.clipboard.writeText(chave).then(() => {
            const btnBuscar = document.getElementById('btn-buscar-sefaz');
            const textoOriginal = btnBuscar.innerText;
            btnBuscar.innerText = "COPIADO! (CTRL+V NO SITE)";
            btnBuscar.style.color = "#ffaa00";
            btnBuscar.style.borderColor = "#ffaa00";

            setTimeout(() => {
                btnBuscar.innerText = textoOriginal;
                btnBuscar.style.color = "#00ff00";
                btnBuscar.style.borderColor = "#00ff00";
            }, 3000);
        }).catch(err => console.log('Erro ao copiar chave:', err));
    } else {
        alert("Estado (UF: " + ufCode + ") não suportado no momento.");
    }
}

function abrirModalDivisor() {
    document.getElementById('modal-divisor-nota').classList.remove('hidden');
    renderizarItens();
}

function fecharModalDivisor() {
    document.getElementById('modal-divisor-nota').classList.add('hidden');
}

function alternarDono(index) {
    itensNotaAtual[index].owner = itensNotaAtual[index].owner === 'HOUSE' ? 'ME' : 'HOUSE';
    renderizarItens();
}

function atualizarQuantidade(index, novaQtd) {
    novaQtd = parseFloat(novaQtd);
    if (isNaN(novaQtd) || novaQtd < 0)
        return;
    itensNotaAtual[index].quantity = novaQtd;
    renderizarItens();
}

async function enviarArquivoNota() {
    const fileInput = document.getElementById('nfe-file');
    if (!fileInput.files.length)
        return;

    // Feedback visual forte de Loading da Inteligência Artificial
    const btnUpload = document.getElementById('btn-upload-pdf');
    const textoOriginal = btnUpload.innerText;

    btnUpload.innerHTML = "⏳ PROCESSANDO IA... AGUARDE";
    btnUpload.style.color = "#00ffff"; // Muda para azul ciano enquanto pensa
    btnUpload.style.borderColor = "#00ffff";
    btnUpload.disabled = true;
    document.body.style.cursor = "wait"; // Muda o mouse para a ampulheta

    const formData = new FormData();
    formData.append("file", fileInput.files[0]);

    try {
        const response = await fetch('../api/leitor-nota', {method: 'POST', body: formData});
        const data = await response.json();

        if (data.status === 'success') {
            itensNotaAtual = data.items.map(item => {
                return {
                    ...item,
                    owner: 'HOUSE',
                    originalQty: item.quantity,
                    unitPrice: item.price / item.quantity
                };
            });
            abrirModalDivisor();
            document.getElementById('btn-revisar-itens').style.display = 'block';
        } else {
            alert(data.message); // Agora o erro da IA aparece bonito aqui
        }
    } catch (error) {
        alert("Falha crítica de comunicação com o servidor.");
    } finally {
        // Restaura o botão ao normal quando a IA termina
        btnUpload.innerText = textoOriginal;
        btnUpload.style.color = "#ffaa00";
        btnUpload.style.borderColor = "#ffaa00";
        btnUpload.disabled = false;
        document.body.style.cursor = "default";
        fileInput.value = "";
    }
}

function renderizarItens() {
    const listArea = document.getElementById('nfe-items-list');
    listArea.innerHTML = '';
    let totalCasa = 0, totalMeu = 0;

    itensNotaAtual.forEach((item, index) => {
        if (item.owner === 'HOUSE')
            totalCasa += item.price;
        else
            totalMeu += item.price;

        const div = document.createElement('div');
        div.style.cssText = "display: flex; align-items: center; justify-content: space-between; padding: 10px 0; border-bottom: 1px dashed #222; transition: 0.3s; gap: 5px;";

        const corDono = item.owner === 'HOUSE' ? '#00ff00' : '#ffaa00';
        const bgBtn = item.owner === 'HOUSE' ? '#003300' : '#332200';
        const txtBtn = item.owner === 'HOUSE' ? '🏠 CASA' : '👤 MEU';

        // ATUALIZADO: Usando flex: 3 para o nome, dando muito mais espaço para ele não cortar
        div.innerHTML = `
            <div style="flex: 3; color: #ccc; font-size: 0.85em; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; padding-right: 5px;" title="${item.name}">${item.name}</div>
            
            <div style="flex: 1; text-align: center;">
                <input type="number" step="0.01" value="${item.quantity}" onchange="atualizarQuantidade(${index}, this.value)" style="width: 100%; max-width: 60px; background: #000; color: #fff; border: 1px solid #444; text-align: center; border-radius: 3px; padding: 4px;">
            </div>
            
            <div style="flex: 1.5; text-align: right; color: ${corDono}; font-weight: bold; font-size: 0.95em;">
                R$ ${item.price.toFixed(2)}
            </div>
            
            <div style="flex: 1.5; text-align: right;">
                <button type="button" onclick="alternarDono(${index})" class="btn-matrix" style="padding: 5px; font-size: 0.7em; border-color: ${corDono}; color: ${corDono}; background: ${bgBtn}; width: 100%; max-width: 80px;">
                    ${txtBtn}
                </button>
            </div>
        `;
        listArea.appendChild(div);
    });

    document.getElementById('total-casa').innerText = totalCasa.toFixed(2);
    document.getElementById('total-meu').innerText = totalMeu.toFixed(2);
}

function salvarDivisao() {
    const totalCasaStr = document.getElementById('total-casa').innerText;
    const campoValor = document.getElementById('trans-amount');

    if (campoValor) {
        campoValor.value = totalCasaStr;
        campoValor.style.backgroundColor = 'rgba(0, 255, 0, 0.2)';
        setTimeout(() => campoValor.style.backgroundColor = '', 1000);
    }

// MOSTRA o botão de revisão para permitir alterações posteriores
    if (itensNotaAtual.length > 0) {
        document.getElementById('btn-revisar-itens').style.display = 'block';
    }

    fecharModalDivisor();
}

function formatarChaveSefaz(input) {
    // 1. Remove tudo o que não for número (ignora espaços, letras, traços)
    let numeros = input.value.replace(/\D/g, '');

    // 2. Trava exatamente em 44 números
    if (numeros.length > 44) {
        numeros = numeros.substring(0, 44);
    }

    // 3. Devolve para o campo formatado com um espaço a cada 4 números
    input.value = numeros.replace(/(\d{4})(?=\d)/g, '$1 ');
}

// ==========================================
// ADIÇÃO MANUAL DE ITENS AO DIVISOR
// ==========================================
function adicionarItemManual() {
    const nameInput = document.getElementById('manual-name');
    const qtyInput = document.getElementById('manual-qty');
    const priceInput = document.getElementById('manual-price');

    const name = nameInput.value.trim().toUpperCase();
    const qty = parseFloat(qtyInput.value);
    const price = parseFloat(priceInput.value);

    // Validação de segurança
    if (!name || isNaN(qty) || isNaN(price) || qty <= 0 || price <= 0) {
        alert("Preencha o nome, a quantidade e o valor total corretamente.");
        return;
    }

    // Cria o objeto no mesmo padrão do Java/Gemini
    const novoItem = {
        id: 'manual_' + Date.now(), // Gera um ID único
        name: name,
        quantity: qty,
        price: price,
        owner: 'HOUSE', // Padrão: vai para a casa
        originalQty: qty,
        unitPrice: price / qty // Descobre o valor unitário para a matemática não quebrar
    };

    // Joga na lista e redesenha a tela
    itensNotaAtual.push(novoItem);
    renderizarItens();

    // Limpa os campos para o próximo item
    nameInput.value = '';
    qtyInput.value = '';
    priceInput.value = '';
    nameInput.focus();
}
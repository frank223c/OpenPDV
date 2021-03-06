package br.com.openpdv.controlador.comandos;

import br.com.openpdv.controlador.core.CoreService;
import br.com.phdss.Util;
import br.com.openpdv.modelo.core.EComandoSQL;
import br.com.openpdv.modelo.core.OpenPdvException;
import br.com.openpdv.modelo.core.Sql;
import br.com.openpdv.modelo.core.filtro.ECompara;
import br.com.openpdv.modelo.core.filtro.FiltroNumero;
import br.com.openpdv.modelo.core.parametro.*;
import br.com.openpdv.modelo.ecf.EcfPagamento;
import br.com.openpdv.modelo.ecf.EcfTroca;
import br.com.openpdv.modelo.ecf.EcfTrocaProduto;
import br.com.openpdv.modelo.ecf.EcfVenda;
import br.com.openpdv.modelo.ecf.EcfVendaProduto;
import br.com.openpdv.modelo.produto.ProdGrade;
import br.com.openpdv.visao.core.Caixa;
import br.com.phdss.ECF;
import br.com.phdss.EComando;
import br.com.phdss.IECF;
import br.com.phdss.TEF;
import br.com.phdss.controlador.PAF;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.swing.JOptionPane;
import org.apache.log4j.Logger;

/**
 * Classe que realiza a acao de fechar uma venda.
 *
 * @author Pedro H. Lira
 */
public class ComandoFecharVenda implements IComando {

    private Logger log;
    private CoreService service;
    private List<EcfPagamento> pagamentos;
    private double bruto;
    private double acres_desc;
    private EcfVenda venda;
    private IECF ecf;

    /**
     * Construtor padrao.
     *
     * @param pagamentos a lista de pagamentos realizados.
     * @param bruto o valor total da venda.
     * @param acres_desc valor de acrescimo (positivo) ou desconto (negativo).
     * @param troco o valor do troco da venda.
     */
    public ComandoFecharVenda(List<EcfPagamento> pagamentos, double bruto, double acres_desc, double troco) {
        this.log = Logger.getLogger(ComandoFecharVenda.class);
        this.service = new CoreService();
        this.pagamentos = pagamentos;
        this.bruto = bruto;
        this.acres_desc = acres_desc;
        this.venda = Caixa.getInstancia().getVenda();
        this.ecf = ECF.getInstancia();
    }

    @Override
    public void executar() throws OpenPdvException {
        try {
            // fecha a venda no cupom
            TEF.bloquear(true);
            fecharVendaECF();
            // salva no bd
            fecharVendaBanco();
            // salva os pagamentos
            new ComandoSalvarPagamento(pagamentos).executar();
            // coloca na tela
            fecharVendaTela();
            TEF.bloquear(false);
            // atualizando o servidor
            if (!Util.getConfig().getProperty("sinc.servidor").endsWith("localhost")) {
                new Thread(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            List<EcfVenda> lista = new ArrayList<>();
                            venda.setEcfPagamentos(pagamentos);
                            lista.add(venda);
                            lista = ComandoEnviarDados.getInstancia().enviar("venda", lista);
                            // marca a venda como sincronizada
                            if (!lista.isEmpty()) {
                                venda.setEcfVendaSinc(true);
                                service.salvar(venda);
                            }
                        } catch (Exception ex) {
                            log.error("Nao enviou no momento a venda com id -> " + venda.getId(), ex);
                        }
                    }
                }).start();
            }
        } finally {
            TEF.bloquear(false);
        }
    }

    @Override
    public void desfazer() throws OpenPdvException {
        // comando nao aplicavel.
    }

    /**
     * Metodo para fechar uma venda no ECF.
     *
     * @exception OpenPdvException dispara caso nao consiga executar.
     */
    public void fecharVendaECF() throws OpenPdvException {
        try {
            // sub totaliza
            String AD = Util.formataNumero(acres_desc, 1, 2, false).replace(",", ".");
            StringBuilder sb = new StringBuilder();
            sb.append("MD-5: ").append(PAF.AUXILIAR.getProperty("out.autenticado")).append(IECF.SL);

            // identifica o operador do caixa e o vendedor
            String operador = "OPERADOR: " + venda.getSisUsuario().getSisUsuarioLogin();
            if (venda.getSisVendedor() != null) {
                operador += " - VENDEDOR: " + venda.getSisVendedor().getSisUsuarioLogin();
            }
            sb.append(operador).append(IECF.SL);

            // caso nao tenha sido informado o cliente
            if (venda.getSisCliente() == null) {
                sb.append("CONSUMIDOR NAO INFORMOU O CPF/CNPJ").append(IECF.SL);
            } else if (Caixa.getInstancia().getVenda().isInformouCliente() == false && venda.getSisCliente().isCpfCupom()) {
                sb.append("CNPJ/CPF consumidor:").append(venda.getSisCliente().getSisClienteDoc()).append(IECF.SL);
                if (!venda.getSisCliente().getSisClienteNome().equals("")) {
                    sb.append("NOME:").append(venda.getSisCliente().getSisClienteNome()).append(IECF.SL);
                }
                if (!venda.getSisCliente().getSisClienteEndereco().equals("")) {
                    sb.append("END:").append(venda.getSisCliente().getSisClienteEndereco()).append(IECF.SL);
                }
            }

            // caso seja no estado de MG, colocar o minas legal
            if (PAF.AUXILIAR.getProperty("paf.minas_legal").equalsIgnoreCase("SIM")) {
                sb.append("MINAS LEGAL: ");
                sb.append(PAF.AUXILIAR.getProperty("cli.cnpj")).append(" ");
                sb.append(Util.formataData(venda.getEcfVendaData(), "ddMMyyyy")).append(" ");
                sb.append(Util.formataNumero(bruto + acres_desc, 0, 2, true).replace(",", "")).append(IECF.SL);
            } else if (PAF.AUXILIAR.getProperty("paf.cupom_mania").equalsIgnoreCase("SIM")) {
                // caso seja no estado de RJ, colocar o cupom mania
                sb.append("CUPOM MANIA. CONCORRA A PRÊMIOS").append(IECF.SL);
                sb.append("ENVIE SMS P/ 6789: ");
                sb.append(Util.formataNumero(PAF.AUXILIAR.getProperty("cli.ie"), 8, 0, false));
                sb.append(Util.formataData(venda.getEcfVendaData(), "ddMMyy"));
                sb.append(Util.formataNumero(venda.getEcfVendaCoo(), 6, 0, false));
                sb.append(Util.formataNumero(Caixa.getInstancia().getImpressora().getEcfImpressoraCaixa(), 3, 0, false)).append(IECF.SL);
            } else if (PAF.AUXILIAR.getProperty("paf.paraiba_legal").equalsIgnoreCase("SIM")) {
                // caso seja no estado de PB, colocar o paraiba legal
                sb.append("PARAÍBA LEGAL - RECEITA CIDADÃ").append(IECF.SL);
                sb.append("TORPEDO PREMIADO:").append(IECF.SL);
                sb.append(Util.formataNumero(PAF.AUXILIAR.getProperty("cli.ie"), 9, 0, false));
                sb.append(Util.formataData(venda.getEcfVendaData(), "ddMMyyyy"));
                sb.append(Util.formataNumero(venda.getEcfVendaCoo(), 6, 0, false));
                sb.append(Util.formataNumero(bruto + acres_desc, 0, 2, true).replace(",", "")).append(IECF.SL);
            }

            // caso a opcao de mostrar os valores de impostos esteja ativa
            boolean mostraIbpt = Boolean.valueOf(Util.getConfig().getProperty("nfe.ibpt"));
            if (mostraIbpt) {
                double impostos = 0.00;
                double porcent = acres_desc / bruto;

                for (EcfVendaProduto vp : venda.getEcfVendaProdutos()) {
                    if (!vp.getEcfVendaProdutoCancelado() && vp.getProdProduto().getIbpt() != null) {
                        char ori = vp.getProdProduto().getProdProdutoOrigem();
                        double taxa = (ori == '0' || ori == '3' || ori == '4' || ori == '5') ? vp.getProdProduto().getIbpt().getIbptAliqNac() : vp.getProdProduto().getIbpt().getIbptAliqImp();
                        double rateado = vp.getEcfVendaProdutoBruto() * porcent;
                        impostos += (vp.getEcfVendaProdutoBruto() + rateado) * vp.getEcfVendaProdutoQuantidade() * taxa / 100;
                    }
                }
                double porcentagem = impostos / (bruto + acres_desc) * 100;
                sb.append("Val Aprox Trib R$ ");
                sb.append(Util.formataNumero(impostos, 1, 2, false).replace(",", ".")).append(" [");
                sb.append(Util.formataNumero(porcentagem, 1, 2, false).replace(",", ".")).append("%] Fonte: IBPT").append(IECF.SL);
            }

            // caso tenha informado uma observacao na venda
            if(venda.getEcfVendaObservacao() != null && !venda.getEcfVendaObservacao().equals("")){
                sb.append("OBS:").append(venda.getEcfVendaObservacao()).append(IECF.SL);
            }
            
            String[] resp = ecf.enviar(EComando.ECF_SubtotalizaCupom, AD, sb.toString());
            if (IECF.ERRO.equals(resp[0])) {
                log.error("Erro ao fechar a venda. -> " + resp[1]);
                throw new OpenPdvException(resp[1]);
            }
            // soma os pagamento que possuem o mesmo codigo
            SortedMap<String, Double> pags = new TreeMap<>();
            for (EcfPagamento pag : pagamentos) {
                if (pags.containsKey(pag.getEcfPagamentoTipo().getEcfPagamentoTipoCodigo())) {
                    double valor = pag.getEcfPagamentoValor() + pags.get(pag.getEcfPagamentoTipo().getEcfPagamentoTipoCodigo());
                    pags.put(pag.getEcfPagamentoTipo().getEcfPagamentoTipoCodigo(), valor);
                } else {
                    pags.put(pag.getEcfPagamentoTipo().getEcfPagamentoTipoCodigo(), pag.getEcfPagamentoValor());
                }
            }
            // garante que o dinheiro é impressos primeiro
            String dinheiro = Util.getConfig().getProperty("ecf.dinheiro");
            if (pags.containsKey(dinheiro)) {
                String valor = Util.formataNumero(pags.remove(dinheiro), 1, 2, false).replace(",", ".");
                resp = ecf.enviar(EComando.ECF_EfetuaPagamento, dinheiro, valor);
                if (IECF.ERRO.equals(resp[0])) {
                    log.error("Erro ao fechar a venda. -> " + resp[1]);
                    throw new OpenPdvException(resp[1]);
                }
            }
            // garante que a troca é impresso antes de cartoes
            String troca = Util.getConfig().getProperty("ecf.troca");
            if (pags.containsKey(troca)) {
                String valor = Util.formataNumero(pags.remove(troca), 1, 2, false).replace(",", ".");
                resp = ecf.enviar(EComando.ECF_EfetuaPagamento, troca, valor);
                if (IECF.ERRO.equals(resp[0])) {
                    log.error("Erro ao fechar a venda. -> " + resp[1]);
                    throw new OpenPdvException(resp[1]);
                }
            }
            // garante que o cartao presente é impresso antes de cartoes tef
            String presente = Util.getConfig().getProperty("ecf.presente");
            if (pags.containsKey(presente)) {
                String valor = Util.formataNumero(pags.remove(presente), 1, 2, false).replace(",", ".");
                resp = ecf.enviar(EComando.ECF_EfetuaPagamento, presente, valor);
                if (IECF.ERRO.equals(resp[0])) {
                    log.error("Erro ao fechar a venda. -> " + resp[1]);
                    throw new OpenPdvException(resp[1]);
                }
            }
            // garante que o cheque é impresso antes de cartoes tef
            String cheque = Util.getConfig().getProperty("ecf.cheque");
            if (pags.containsKey(cheque)) {
                String valor = Util.formataNumero(pags.remove(cheque), 1, 2, false).replace(",", ".");
                resp = ecf.enviar(EComando.ECF_EfetuaPagamento, cheque, valor);
                if (IECF.ERRO.equals(resp[0])) {
                    log.error("Erro ao fechar a venda. -> " + resp[1]);
                    throw new OpenPdvException(resp[1]);
                }
            }
            // imprime os demais
            for (Entry<String, Double> pag : pags.entrySet()) {
                String valor = Util.formataNumero(pag.getValue(), 1, 2, false).replace(",", ".");
                resp = ecf.enviar(EComando.ECF_EfetuaPagamento, pag.getKey(), valor);
                if (IECF.ERRO.equals(resp[0])) {
                    log.error("Erro ao fechar a venda. -> " + resp[1]);
                    throw new OpenPdvException(resp[1]);
                }
            }
            // fecha a venda
            resp = ecf.enviar(EComando.ECF_FechaCupom);
            if (IECF.ERRO.equals(resp[0])) {
                log.error("Erro ao fechar a venda. -> " + resp[1]);
                throw new OpenPdvException(resp[1]);
            } else {
                // atualiza o gt
                try {
                    resp = ecf.enviar(EComando.ECF_GrandeTotal);
                    if (IECF.OK.equals(resp[0])) {
                        PAF.AUXILIAR.setProperty("ecf.gt", resp[1]);
                        Util.criptografar(null, PAF.AUXILIAR);
                    } else {
                        throw new Exception(resp[1]);
                    }
                } catch (Exception ex) {
                    log.error("Erro ao atualizar o GT. -> ", ex);
                    throw new OpenPdvException("Erro ao atualizar o GT.");
                }
            }
        } catch (OpenPdvException ex) {
            TEF.bloquear(false);
            int escolha = JOptionPane.showOptionDialog(null, "Impressora não responde, tentar novamente?", "TEF",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, new String[]{"SIM", "NÃO"}, JOptionPane.YES_OPTION);
            TEF.bloquear(true);
            if (escolha == JOptionPane.YES_OPTION) {
                fecharVendaECF();
            } else {
                throw new OpenPdvException(ex);
            }
        }
    }

    /**
     * Metodo para fechar uma venda no BD.
     *
     * @exception OpenPdvException dispara caso nao consiga executar.
     */
    public void fecharVendaBanco() throws OpenPdvException {
        // fecha a venda
        List<Sql> sqls = new ArrayList<>();
        venda.setEcfVendaBruto(bruto);
        if (acres_desc > 0) {
            venda.setEcfVendaAcrescimo(Math.abs(acres_desc));
        } else {
            venda.setEcfVendaDesconto(Math.abs(acres_desc));
        }
        venda.setEcfVendaLiquido(bruto + acres_desc);
        venda.setEcfVendaFechada(true);

        // atualiza a troca para ativo = true, se a venda estiver usando uma
        if (venda.getEcfTrocas() != null) {
            for (EcfTroca troca : venda.getEcfTrocas()) {
                FiltroNumero fn1 = new FiltroNumero("ecfTrocaId", ECompara.IGUAL, troca.getEcfTrocaId());
                ParametroBinario pb1 = new ParametroBinario("ecfTrocaAtivo", true);
                ParametroObjeto po = new ParametroObjeto("ecfVenda", venda);
                ParametroGrupo gp1 = new ParametroGrupo(new Parametro[]{pb1, po});
                Sql sql1 = new Sql(troca, EComandoSQL.ATUALIZAR, fn1, gp1);
                sqls.add(sql1);

                // atualiza o estoque
                for (EcfTrocaProduto tp : troca.getEcfTrocaProdutos()) {
                    // fatorando a quantida no estoque
                    double qtd = tp.getEcfTrocaProdutoQuantidade();
                    if (tp.getProdEmbalagem().getProdEmbalagemId() != tp.getProdProduto().getProdEmbalagem().getProdEmbalagemId().intValue()) {
                        qtd *= tp.getProdEmbalagem().getProdEmbalagemUnidade();
                        qtd /= tp.getProdProduto().getProdEmbalagem().getProdEmbalagemUnidade();
                    }
                    // atualiza o estoque
                    ParametroFormula pf1 = new ParametroFormula("prodProdutoEstoque", qtd);
                    FiltroNumero fn2 = new FiltroNumero("prodProdutoId", ECompara.IGUAL, tp.getProdProduto().getId());
                    Sql sql2 = new Sql(tp.getProdProduto(), EComandoSQL.ATUALIZAR, fn2, pf1);
                    sqls.add(sql2);
                    // adiciona estoque da grade caso o produto tenha
                    if (tp.getProdProduto().getProdGrades() != null) {
                        for (ProdGrade grade : tp.getProdProduto().getProdGrades()) {
                            if (grade.getProdGradeBarra().equals(tp.getEcfTrocaProdutoBarra())) {
                                ParametroFormula pf2 = new ParametroFormula("prodGradeEstoque", qtd);
                                FiltroNumero fn3 = new FiltroNumero("prodGradeId", ECompara.IGUAL, grade.getId());
                                Sql sql3 = new Sql(grade, EComandoSQL.ATUALIZAR, fn3, pf2);
                                sqls.add(sql3);
                                break;
                            }
                        }
                    }
                }
            }
        }

        // atualiza estoque e produtos
        double porcentagem = acres_desc / bruto;
        for (EcfVendaProduto vp : venda.getEcfVendaProdutos()) {
            if (porcentagem != 0) {
                double rateado = vp.getEcfVendaProdutoBruto() * porcentagem;
                if (porcentagem > 0) {
                    vp.setEcfVendaProdutoAcrescimo(Math.abs(rateado));
                } else {
                    vp.setEcfVendaProdutoDesconto(Math.abs(rateado));
                }
                vp.setEcfVendaProdutoLiquido(vp.getEcfVendaProdutoBruto() + rateado);
                vp.setEcfVendaProdutoTotal((vp.getEcfVendaProdutoBruto() + rateado) * vp.getEcfVendaProdutoQuantidade());
                service.salvar(vp);
            }

            if (!vp.getEcfVendaProdutoCancelado()) {
                // fatorando a quantida no estoque
                double qtd = vp.getEcfVendaProdutoQuantidade();
                if (vp.getProdEmbalagem().getProdEmbalagemId() != vp.getProdProduto().getProdEmbalagem().getProdEmbalagemId().intValue()) {
                    qtd *= vp.getProdEmbalagem().getProdEmbalagemUnidade();
                    qtd /= vp.getProdProduto().getProdEmbalagem().getProdEmbalagemUnidade();
                }
                // atualiza o estoque
                ParametroFormula pf1 = new ParametroFormula("prodProdutoEstoque", -1 * qtd);
                FiltroNumero fn2 = new FiltroNumero("prodProdutoId", ECompara.IGUAL, vp.getProdProduto().getId());
                Sql sql2 = new Sql(vp.getProdProduto(), EComandoSQL.ATUALIZAR, fn2, pf1);
                sqls.add(sql2);
                // remove estoque da grade caso o produto tenha
                if (vp.getProdProduto().getProdGrades() != null) {
                    for (ProdGrade grade : vp.getProdProduto().getProdGrades()) {
                        if (grade.getProdGradeBarra().equals(vp.getEcfVendaProdutoBarra())) {
                            ParametroFormula pf2 = new ParametroFormula("prodGradeEstoque", -1 * qtd);
                            FiltroNumero fn3 = new FiltroNumero("prodGradeId", ECompara.IGUAL, grade.getId());
                            Sql sql3 = new Sql(grade, EComandoSQL.ATUALIZAR, fn3, pf2);
                            sqls.add(sql3);
                            break;
                        }
                    }
                }
            }
        }
        service.executar(sqls.toArray(new Sql[]{}));

        // recupera a venda do banco completa e atualizada
        this.venda = (EcfVenda) service.salvar(venda);
    }

    /**
     * Metodo para fechar uma venda na Tela.
     *
     * @exception OpenPdvException dispara caso nao consiga executar.
     */
    public void fecharVendaTela() throws OpenPdvException {
        Caixa.getInstancia().getBobina().removeAllElements();
        Caixa.getInstancia().modoDisponivel();
    }
}

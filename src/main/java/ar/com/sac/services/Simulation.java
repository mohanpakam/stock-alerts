package ar.com.sac.services;

import ar.com.sac.model.Quote;
import ar.com.sac.model.operations.Operator;
import ar.com.sac.model.simulator.SimulationResults;
import ar.com.sac.model.simulator.SimulatorParameters;
import ar.com.sac.model.simulator.SimulatorRecord;
import ar.com.sac.model.simulator.SymbolPerformanceStatistics;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Simulation {
   
   private SimulatorParameters parameters;
   private StockService stockService;
   private ExpressionService expressionService;
   private SimulationResults simulationResults = new SimulationResults();
   private StockSimulatorService stockSimulatorService = new StockSimulatorService();
   
   
   //simulation variables
   private String currentSymbol;
   private Quote currentLastQuote;
   private List<Quote> allTheQuotes = new ArrayList<>();
   private Map<String, Integer> indexPerSymbolMap  = new HashMap<>();
   private Map<String, List<Quote>> symbolToQuotesMap  = new HashMap<>();
   private Map<String, SimulatorRecord> positionsMap = new HashMap<>();
   private Map<String, SymbolPerformanceStatistics> performanceBySymbolMap = new HashMap<>();
   private int previousAnalysisDays = 50;
   private SimulatorRecord lastSimulatorRecord;

   public Simulation( SimulatorParameters parameters, StockService stockService, ExpressionService expressionService ){
      this.parameters = parameters;
      this.stockService = stockService;
      this.expressionService = expressionService;
   }
   
   public SimulationResults run(){
      
      try {
         initSimulationVariables();
         List<Quote> quotesAux;
         
         int index;
         for( Quote currentQuote: allTheQuotes){
            currentSymbol = currentQuote.getSymbol();
            quotesAux = symbolToQuotesMap.get( currentSymbol );
            index = indexPerSymbolMap.get( currentSymbol );
            stockSimulatorService.setSimulationQuotes( quotesAux.subList( quotesAux.size() - index, quotesAux.size() ));
            currentLastQuote = stockSimulatorService.getStock( currentSymbol ).getLastQuote();
            if(!tryBuy()){
               if(!trySell()){
                  tryStopLoss();
               }
            }
            indexPerSymbolMap.put( currentSymbol, ++index );
         }
         completeSimulationResults();
         return simulationResults;
      } catch (IOException e) {
         e.printStackTrace();
         throw new RuntimeException( "Error running simulation" );
      }
   }

   private void completeSimulationResults() {
      simulationResults.setFinalLiquity( lastSimulatorRecord.getLiquity() );
      simulationResults.setFinalCapitalBalance( lastSimulatorRecord.getCapitalBalance() );
      simulationResults.setSymbolPerformances( performanceBySymbolMap.values() );
      simulationResults.setQuantityOfOperations( simulationResults.getRecords().size() - 1  );
      simulationResults.setTotalPerformance( lastSimulatorRecord.getCapitalBalance() - parameters.getInitialCapital() );
      simulationResults.setTotalPerformancePercentage( (lastSimulatorRecord.getCapitalBalance() - parameters.getInitialCapital())*100/parameters.getInitialCapital() );
   }

   private void initSimulationVariables() throws IOException {
      lastSimulatorRecord = new SimulatorRecord();
      lastSimulatorRecord.setId( 0 );
      lastSimulatorRecord.setCapitalBalance( parameters.getInitialCapital() );
      lastSimulatorRecord.setLiquity( parameters.getInitialCapital() );
      lastSimulatorRecord.setOrderType( "Initial Investment" );
      simulationResults.addRecord( lastSimulatorRecord );

      List<Quote> quotesAux;
      Calendar from = new GregorianCalendar(parameters.getYearFrom(),0,1);
      Calendar to = new GregorianCalendar(parameters.getYearTo(),11,31);
      for(String symbol : parameters.getSymbols()){
         quotesAux = stockService.getHistory( symbol, from, to );
         allTheQuotes.addAll( quotesAux.subList( 0, quotesAux.size() - previousAnalysisDays ) );
         indexPerSymbolMap.put( symbol, previousAnalysisDays );
         symbolToQuotesMap.put( symbol, quotesAux );
      }
      //allThQuotes must be sorted ASC
      Collections.sort( allTheQuotes, new Comparator<Quote>() {

         @Override
         public int compare( Quote o1, Quote o2 ) {
            return o1.getDate().compareTo( o2.getDate() );
         }} );
   }

   private boolean tryStopLoss() throws IOException {
      boolean sold = false;
      if( positionsMap.get( currentSymbol ) == null ) {
         return false; //There is NOT a position on this symbol
      }
      if(isVacationDay( currentSymbol )){
         return false;
      }
      
      SimulatorRecord positionRecord = positionsMap.get( currentSymbol );
      
      double maxCapitalToLoss = lastSimulatorRecord.getCapitalBalance() * parameters.getStopLossPercentage() / 100d;
      double currentValue = currentLastQuote.getClose().doubleValue() * positionRecord.getOrderAmount();
      if( (positionRecord.getOrderTotalCost() - currentValue) > maxCapitalToLoss ){
         SimulatorRecord sellRecord = sell();
         sellRecord.setOrderType( "Sell on StopLoss" );
         
         simulationResults.addRecord( sellRecord );
         positionsMap.put( currentSymbol, null );
         lastSimulatorRecord = sellRecord;
         sold = true;
         updateStatistics();
      }
      
      return sold;
   }

   private boolean trySell() throws IOException {
      if( positionsMap.get( currentSymbol ) == null ) {
         return false; //There is NOT a position on this symbol
      }
      if(isVacationDay( currentSymbol )){
         return false;
      }
      
      boolean sold = false;
      Operator operator = expressionService.parseSimulatorExpression( parameters.getSellExpression(), this, stockSimulatorService );
      if( operator.evaluate() ){
         SimulatorRecord sellRecord = sell();
        
         simulationResults.addRecord( sellRecord );
         positionsMap.put( currentSymbol, null );
         lastSimulatorRecord = sellRecord;
         sold = true;
         updateStatistics();
      }
      return sold;
   }

   private boolean isVacationDay( String symbol ) throws IOException {
      if(currentLastQuote.getVolume() == 0L){
         //you cannot operate on vacation day
         return true;
      }
      return false;
   }

   private SimulatorRecord sell() throws IOException {
      SimulatorRecord positionRecord = positionsMap.get( currentSymbol );
      double sellAux = currentLastQuote.getClose().doubleValue() * positionRecord.getOrderAmount();
      double commission = sellAux * parameters.getCommissionPercentage() / 100d;
      double totalEarned = sellAux - commission;
      
      SimulatorRecord sellRecord = new SimulatorRecord();
      sellRecord.setRelatedRecordId( positionRecord.getId() );
      sellRecord.setId( lastSimulatorRecord.getId() + 1 );
      sellRecord.setOrderAmount( positionRecord.getOrderAmount() );
      sellRecord.setOrderPrice( currentLastQuote.getClose().doubleValue() );
      sellRecord.setOrderDate( currentLastQuote.getDate() );
      sellRecord.setLiquity( lastSimulatorRecord.getLiquity() + totalEarned );
      sellRecord.setOrderTotalCost( totalEarned );
      sellRecord.setOrderSymbol( currentSymbol );
      sellRecord.setOrderType( "Sell" );
      sellRecord.setCapitalBalance( lastSimulatorRecord.getCapitalBalance() + totalEarned - (positionRecord.getOrderAmount() * positionRecord.getOrderPrice()) );
      sellRecord.setOperationPerformance( sellRecord.getOrderTotalCost() - positionRecord.getOrderTotalCost() );
      return sellRecord;
   }

   private boolean tryBuy() throws IOException {
      if( positionsMap.get( currentSymbol ) != null ) {
         return false; //already has position on this symbol
      }
      
      if(isVacationDay( currentSymbol )){
         return false;
      }
      boolean bought = false;
      Operator operator = expressionService.parseSimulatorExpression( parameters.getBuyExpression(), this, stockSimulatorService );
      
      if( operator.evaluate() ){
         if(lastSimulatorRecord.getLiquity() >= parameters.getPositionMinimumValue()){
            double buyPrice = calculateBuyPrice();
            double stockPrice = currentLastQuote.getClose().doubleValue();
            int amount = (int)(buyPrice / stockPrice);
            double stocksValue = amount * stockPrice;
            double commission =  stocksValue * parameters.getCommissionPercentage() / 100d;
            double orderValue = stocksValue + commission;
            //make the buy
            SimulatorRecord buyRecord = new SimulatorRecord();
            buyRecord.setId( lastSimulatorRecord.getId() + 1 );
            buyRecord.setLiquity( lastSimulatorRecord.getLiquity() - orderValue );
            buyRecord.setOrderAmount( amount );
            buyRecord.setOrderPrice( stockPrice );
            buyRecord.setOrderSymbol( currentSymbol );
            buyRecord.setOrderDate( currentLastQuote.getDate() );
            buyRecord.setOrderTotalCost( orderValue );
            buyRecord.setOrderType( "Buy" );
            buyRecord.setCapitalBalance( lastSimulatorRecord.getCapitalBalance() - commission );
            
            simulationResults.addRecord( buyRecord );
            positionsMap.put( currentSymbol, buyRecord );
            lastSimulatorRecord = buyRecord;
            bought = true;
         }
      }
      return bought;
   }

   private double calculateBuyPrice() {
      double percentage = parameters.getPositionPercentage();
      double aux = (percentage / 100d) * lastSimulatorRecord.getCapitalBalance();
      if(aux > parameters.getPositionMaximumValue()){
         aux = parameters.getPositionMaximumValue();
      }else if( aux < parameters.getPositionMinimumValue()){
         aux = parameters.getPositionMinimumValue();
      }
      
      if( lastSimulatorRecord.getLiquity() < aux ){
         aux = parameters.getPositionMinimumValue();
      }
      
      double estimatedComission = aux * parameters.getCommissionPercentage() /100d;
      
      return aux - estimatedComission;
   }
   
   private void updateStatistics() {
      SymbolPerformanceStatistics symbolPerformance = performanceBySymbolMap.get( currentSymbol );
      if(symbolPerformance == null){
         symbolPerformance = new SymbolPerformanceStatistics();
         symbolPerformance.setSymbol( currentSymbol );
         performanceBySymbolMap.put( currentSymbol, symbolPerformance );
      }
      
      if(lastSimulatorRecord.getOrderType().startsWith( "Sell" )){
         symbolPerformance.setPerformance( symbolPerformance.getPerformance() + lastSimulatorRecord.getOperationPerformance() );
         if(lastSimulatorRecord.getOperationPerformance()>0){
            symbolPerformance.incPositiveSales();
         }else{
            symbolPerformance.incNegativeSales();
         }
      }
   }

   
   /**
    * @return the currentSymbol
    */
   public synchronized String getCurrentSymbol() {
      return currentSymbol;
   }

   
   /**
    * @return the currentLastQuote
    */
   public synchronized Quote getCurrentLastQuote() {
      return currentLastQuote;
   }

   
   /**
    * @return the positionsMap
    */
   public synchronized SimulatorRecord getPosition( String symbol ) {
      return positionsMap.get( symbol );
   }
   
   

}
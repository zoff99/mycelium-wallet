/*
 * Copyright 2013, 2014 Megion Research and Development GmbH
 *
 * Licensed under the Microsoft Reference Source License (MS-RSL)
 *
 * This license governs use of the accompanying software. If you use the software, you accept this license.
 * If you do not accept the license, do not use the software.
 *
 * 1. Definitions
 * The terms "reproduce," "reproduction," and "distribution" have the same meaning here as under U.S. copyright law.
 * "You" means the licensee of the software.
 * "Your company" means the company you worked for when you downloaded the software.
 * "Reference use" means use of the software within your company as a reference, in read only form, for the sole purposes
 * of debugging your products, maintaining your products, or enhancing the interoperability of your products with the
 * software, and specifically excludes the right to distribute the software outside of your company.
 * "Licensed patents" means any Licensor patent claims which read directly on the software as distributed by the Licensor
 * under this license.
 *
 * 2. Grant of Rights
 * (A) Copyright Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free copyright license to reproduce the software for reference use.
 * (B) Patent Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free patent license under licensed patents for reference use.
 *
 * 3. Limitations
 * (A) No Trademark License- This license does not grant you any rights to use the Licensor’s name, logo, or trademarks.
 * (B) If you begin patent litigation against the Licensor over patents that you think may apply to the software
 * (including a cross-claim or counterclaim in a lawsuit), your license to the software ends automatically.
 * (C) The software is licensed "as-is." You bear the risk of using it. The Licensor gives no express warranties,
 * guarantees or conditions. You may have additional consumer rights under your local laws which this license cannot
 * change. To the extent permitted under your local laws, the Licensor excludes the implied warranties of merchantability,
 * fitness for a particular purpose and non-infringement.
 */

package com.mycelium.wallet;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import com.google.common.collect.ImmutableList;
import com.mycelium.wapi.wallet.currency.ExchangeRateProvider;
import com.mycelium.wapi.api.Wapi;
import com.mycelium.wapi.api.WapiException;
import com.mycelium.wapi.api.request.QueryExchangeRatesRequest;
import com.mycelium.wapi.api.response.QueryExchangeRatesResponse;
import com.mycelium.wapi.model.ExchangeRate;

import java.util.*;

public class ExchangeRateManager implements ExchangeRateProvider {
   private static final int MAX_RATE_AGE_MS = 5 * 1000 * 60;
   private static final String EXCHANGE_DATA = "wapi_exchange_rates";

   public interface Observer {
      void refreshingExchangeRatesSucceeded();
      void refreshingExchangeRatesFailed();
   }

   private final Context _applicationContext;
   private final Wapi _api;

   private volatile List<String> _fiatCurrencies;
   private Map<String, QueryExchangeRatesResponse> _latestRates;
   private long _latestRatesTime;
   private volatile Fetcher _fetcher;
   private final Object _requestLock = new Object();
   private final List<Observer> _subscribers;
   private String _currentExchangeSourceName;

   ExchangeRateManager(Context applicationContext, Wapi api) {
      _applicationContext = applicationContext;
      _api = api;
      _latestRates = null;
      _latestRatesTime = 0;
      _currentExchangeSourceName = getPreferences().getString("currentRateName", null);

      _subscribers = new LinkedList<Observer>();
      _latestRates = new HashMap<String, QueryExchangeRatesResponse>();
   }

   public synchronized void subscribe(Observer subscriber) {
      _subscribers.add(subscriber);
   }

   public synchronized void unsubscribe(Observer subscriber) {
      _subscribers.remove(subscriber);
   }

   private class Fetcher implements Runnable {
      public void run() {
         try {
            List<QueryExchangeRatesResponse> responses = new ArrayList<QueryExchangeRatesResponse>();
            List<String> selectedCurrencies;
            synchronized (_requestLock) {
               selectedCurrencies = new ArrayList<String>(_fiatCurrencies);
            }
            for (String currency : selectedCurrencies) {
               responses.add(_api.queryExchangeRates(new QueryExchangeRatesRequest(Wapi.VERSION, currency)).getResult());
            }
            synchronized (_requestLock) {
               setLatestRates(responses);
               _fetcher = null;
               notifyRefreshingExchangeRatesSucceeded();
            }
         } catch (WapiException e) {
            // we failed to get the exchange rate
            synchronized (_requestLock) {
               _fetcher = null;
               notifyRefreshingExchangeRatesFailed();
            }
         }
      }
   }

   private synchronized void notifyRefreshingExchangeRatesSucceeded() {
      for (final Observer s : _subscribers) {
         s.refreshingExchangeRatesSucceeded();
      }
   }

   private synchronized void notifyRefreshingExchangeRatesFailed() {
      for (final Observer s : _subscribers) {
         s.refreshingExchangeRatesFailed();
      }
   }

   // only refresh if last refresh is old
   public void requestOptionalRefresh(){
      if (System.currentTimeMillis() - _latestRatesTime > (MAX_RATE_AGE_MS/2) ){
         requestRefresh();
      }
   }

   public void requestRefresh() {
      synchronized (_requestLock) {
         // Only start fetching if we are not already on it
         if (_fetcher == null) {
            _fetcher = new Fetcher();
            Thread t = new Thread(_fetcher);
            t.setDaemon(true);
            t.start();
         }
      }
   }

   private synchronized void setLatestRates(List<QueryExchangeRatesResponse> latestRates) {
      _latestRates = new HashMap<String, QueryExchangeRatesResponse>();
      for (QueryExchangeRatesResponse response : latestRates) {
         _latestRates.put(response.currency, response);
      }
      _latestRatesTime = System.currentTimeMillis();

      if (_currentExchangeSourceName == null) {
         // This only happens the first time the wallet picks up exchange rates.
         // We will default to the first one in the list
         if (latestRates.size() > 0 && latestRates.get(0).exchangeRates.length > 0) {
            _currentExchangeSourceName = latestRates.get(0).exchangeRates[0].name;
         }
      }
   }

   /**
    * Get the name of the current exchange rate. May be null the first time the
    * app is running
    */
   public String getCurrentExchangeSourceName() {
      return _currentExchangeSourceName;
   }

   /**
    * Get the names of the currently available exchange rates. May be empty the
    * first time the app is running
    */
   public synchronized List<String> getExchangeSourceNames() {
      List<String> result = new LinkedList<String>();
      //check whether we have any rates
      if (_latestRates.isEmpty()) return result;
      QueryExchangeRatesResponse latestRates =  _latestRates.values().iterator().next();
      if (latestRates != null) {
         for (ExchangeRate r : latestRates.exchangeRates) {
            result.add(r.name);
         }
      }
      return result;
   }

   public synchronized void setCurrentExchangeSourceName(String name) {
      _currentExchangeSourceName = name;
      getEditor().putString("currentRateName", _currentExchangeSourceName).commit();
   }

   /**
    * Get the exchange rate for the specified currency.
    * <p/>
    * Returns null if the current rate is too old
    * In that the case the caller could choose to call refreshRates() and listen
    * for callbacks. If a rate is returned the contained price may be null if
    * the currently chosen exchange source is not available.
    */
   @Override
   public synchronized ExchangeRate getExchangeRate(String currency) {
      if (_latestRates == null || _latestRates.isEmpty() || !_latestRates.containsKey(currency))  {
         return null;
      }
      if (_latestRatesTime + MAX_RATE_AGE_MS < System.currentTimeMillis()) {
         //rate is too old, source seems to not be available
         //we return a rate with null price to indicate there is something wrong with the exchange rate source
         return ExchangeRate.missingRate(_currentExchangeSourceName, System.currentTimeMillis(),  currency);
      }
      for (ExchangeRate r : _latestRates.get(currency).exchangeRates) {
         if (r.name.equals(_currentExchangeSourceName)) {
            //if the price is 0, obviously something went wrong
            if (r.price.equals(0d)) {
               //we return an exchange rate with null price -> indicating missing rate
               return ExchangeRate.missingRate(_currentExchangeSourceName, System.currentTimeMillis(),  currency);
            }
            //everything is fine, return the rate
            return r;
         }
      }
      if (_currentExchangeSourceName != null) {
         // We end up here if the exchange is no longer on the list
         return ExchangeRate.missingRate(_currentExchangeSourceName, System.currentTimeMillis(),  currency);
      }
      return null;
   }

   private SharedPreferences.Editor getEditor() {
      return _applicationContext.getSharedPreferences(EXCHANGE_DATA, Activity.MODE_PRIVATE).edit();
   }

   private SharedPreferences getPreferences() {
      return _applicationContext.getSharedPreferences(EXCHANGE_DATA, Activity.MODE_PRIVATE);
   }

   // set for which fiat currencies we should get fx rates for
   void setCurrencyList(Set<String> currencies) {
      synchronized (_requestLock) {
         // copy list to prevent changes from outside
         ImmutableList.Builder<String> listBuilder = new ImmutableList.Builder<String>().addAll(currencies);
         _fiatCurrencies = listBuilder.build();
      }

      requestRefresh();
   }
}

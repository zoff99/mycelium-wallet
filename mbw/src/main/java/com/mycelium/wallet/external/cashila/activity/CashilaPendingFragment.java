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

package com.mycelium.wallet.external.cashila.activity;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.view.ActionMode;
import android.view.*;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import butterknife.ButterKnife;
import butterknife.BindView;
import com.megiontechnologies.Bitcoins;
import com.mycelium.wallet.MbwManager;
import com.mycelium.wallet.R;
import com.mycelium.wallet.Utils;
import com.mycelium.wallet.activity.modern.Toaster;
import com.mycelium.wallet.external.cashila.api.CashilaService;
import com.mycelium.wallet.external.cashila.api.response.BillPay;
import com.mycelium.wallet.external.cashila.api.response.BillPayStatus;
import com.squareup.otto.Bus;
import rx.Observer;
import rx.android.schedulers.AndroidSchedulers;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The "pending payments" fragment
 */
public class CashilaPendingFragment extends Fragment {
   @BindView(R.id.lvPending) ListView lvPending;
   @BindView(R.id.tvEmpty) TextView tvEmpty;
   @BindView(R.id.pbPendingLoading) ProgressBar pbPendingLoading;

   private CashilaService cs;
   private MbwManager mbw;
   private Bus eventBus;
   private PendingBillsAdapter pendingBillsAdapter;
   private ActionMode currentActionMode;


   /**
    * Returns a new instance of this fragment for the given section
    * number.
    */
   public static CashilaPendingFragment newInstance() {
      CashilaPendingFragment fragment = new CashilaPendingFragment();
      Bundle args = new Bundle();
      fragment.setArguments(args);
      return fragment;
   }

   public CashilaPendingFragment() {

   }

   @Override
   public View onCreateView(LayoutInflater inflater, ViewGroup container,
                            Bundle savedInstanceState) {
      View rootView = inflater.inflate(R.layout.ext_cashila_payments_fragment_pending, container, false);
      ButterKnife.bind(this, rootView);

      mbw = MbwManager.getInstance(this.getActivity());
      cs = ((CashilaPaymentsActivity) getActivity()).getCashilaService();
      eventBus = mbw.getEventBus();
      lvPending.setEmptyView(tvEmpty);

      getBillPays(true, true);

      return rootView;
   }

   public void refresh(boolean showProgress) {
      getBillPays(showProgress, false);
   }

   // fetches the list of current payable invoices from the server
   private void getBillPays(boolean showProgress, boolean fromCache) {
      final ProgressDialog progressDialog;
      if (showProgress) {
         progressDialog = ProgressDialog.show(this.getActivity(), getResources().getString(R.string.cashila), getResources().getString(R.string.cashila_fetching), true);
      } else {
         progressDialog = null;
      }

      pbPendingLoading.setVisibility(View.VISIBLE);
      cs.getAllBillPays(fromCache)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(new Observer<List<BillPay>>() {
               @Override
               public void onCompleted() {
                  if (progressDialog != null) {
                     progressDialog.dismiss();
                  }
               }

               @Override
               public void onError(Throwable e) {
                  if (progressDialog != null) {
                     progressDialog.dismiss();
                  }
               }

               @Override
               public void onNext(List<BillPay> billPays) {
                  pbPendingLoading.setVisibility(View.GONE);

                  Collections.sort(billPays, new Comparator<BillPay>() {
                     @Override
                     public int compare(BillPay lhs, BillPay rhs) {
                        return lhs.getSortOrder() < rhs.getSortOrder() ? -1 : (lhs.getSortOrder() == rhs.getSortOrder() ? 0 : 1);
                     }
                  });

                  pendingBillsAdapter = new PendingBillsAdapter(getActivity(), billPays);
                  lvPending.setAdapter(pendingBillsAdapter);
                  finishActionMode();
               }
            });
   }

   private void finishActionMode() {
      if (currentActionMode != null) {
         currentActionMode.finish();
      }
   }

   @Override
   public void onPause() {
      super.onPause();
      finishActionMode();
   }

   @Override
   public void setUserVisibleHint(boolean isVisibleToUser) {
      super.setUserVisibleHint(isVisibleToUser);
      if (!isVisibleToUser) {
         finishActionMode();
      }
   }

   // Adapter for Pending ListView
   private class PendingBillsAdapter extends android.widget.ArrayAdapter<BillPay> {
      private final LayoutInflater inflater;

      public PendingBillsAdapter(Context context, List<BillPay> objects) {
         super(context, 0, objects);
         inflater = LayoutInflater.from(context);
      }

      @Override
      public long getItemId(int position) {
         return super.getItemId(position);
      }

      @Override
      public View getView(final int position, View convertView, ViewGroup parent) {
         if (convertView == null) {
            convertView = inflater.inflate(R.layout.ext_cashila_pending_row, null);
         }

         final BillPay billPay = getItem(position);

         TextView tvName = ButterKnife.findById(convertView, R.id.tvName);
         TextView tvAmount = ButterKnife.findById(convertView, R.id.tvAmount);
         TextView tvOutstandingAmount = ButterKnife.findById(convertView, R.id.tvOutstandingAmount);
         TextView tvStatus = ButterKnife.findById(convertView, R.id.tvStatus);
         TextView tvFee = ButterKnife.findById(convertView, R.id.tvFee);
         TextView tvSectionHeader = ButterKnife.findById(convertView, R.id.tvSectionHeader);
         TextView tvReference = ButterKnife.findById(convertView, R.id.tvReference);
         TextView tvDate = ButterKnife.findById(convertView, R.id.tvDate);

         if (billPay.recipient != null) {
            tvName.setText(billPay.recipient.name);
         } else {
            tvName.setText("");
         }

         if (billPay.payment != null && billPay.payment.amount != null) {
            tvAmount.setText(Utils.formatFiatValueAsString(billPay.payment.amount) + " " + billPay.payment.currency);
            tvReference.setText(billPay.payment.reference);
         }

         tvStatus.setText(billPay.status.getLocalizedString(getActivity()));

         if (billPay.details != null) {
            String fee;
            if (billPay.details.fee != null) {
               fee = Utils.formatFiatValueAsString(billPay.details.fee) + " " + billPay.payment.currency;
            } else {
               fee = "???";
            }
            tvFee.setText(getResources().getString(R.string.cashila_fee, fee));

            // if there is already an amount deposited but still outstanding we have underpaid (maybe because of some latency of
            // the bitcoin network and/or changes in exchange rate)

            BigDecimal amountDeposited = billPay.details.amountDeposited == null ? BigDecimal.ZERO : billPay.details.amountDeposited;
            BigDecimal amountToDeposit = billPay.details.amountToDeposit == null ? BigDecimal.ZERO : billPay.details.amountToDeposit;
            if (BigDecimal.ZERO.compareTo(amountDeposited) < 0 && BigDecimal.ZERO.compareTo(amountToDeposit) < 0) {
               tvOutstandingAmount.setVisibility(View.VISIBLE);
               long satoshisOutstanding = Bitcoins.nearestValue(amountToDeposit).getLongValue();
               tvOutstandingAmount.setText(String.format(
                     getResources().getString(R.string.cashila_amount_outstanding),
                     mbw.getBtcValueString(satoshisOutstanding)));
            } else {
               tvOutstandingAmount.setVisibility(View.GONE);
            }
            tvDate.setText(Utils.getFormattedDate(getContext(), billPay.details.createdAt));
         }

         if (position == 0) {
            tvSectionHeader.setVisibility(View.VISIBLE);
         } else if (getItem(position - 1).getSortOrder() != billPay.getSortOrder()) {
            tvSectionHeader.setVisibility(View.VISIBLE);
         } else {
            tvSectionHeader.setVisibility(View.GONE);
         }
         if (billPay.isPayable()) {
            tvSectionHeader.setText(getResources().getString(R.string.cashila_pending));
         } else if (billPay.status.equals(BillPayStatus.uploaded)) {
            tvSectionHeader.setText(getResources().getString(R.string.cashila_uploaded_title));
         } else {
            tvSectionHeader.setText(getResources().getString(R.string.cashila_done));
         }


         final ActionBarActivity actionBarActivity = (ActionBarActivity) getActivity();

         final RelativeLayout rlReference = ButterKnife.findById(convertView, R.id.rlReference);
         convertView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
               currentActionMode = actionBarActivity.startSupportActionMode(new ActionMode.Callback() {
                  @Override
                  public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
                     if (billPay.isPayable()) {
                        actionMode.getMenuInflater().inflate(R.menu.ext_cashila_pending_payments_menu, menu);
                        currentActionMode = actionMode;
                     }
                     rlReference.setVisibility(View.VISIBLE);
                     lvPending.setItemChecked(position, true);
                     return true;
                  }

                  @Override
                  public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
                     rlReference.setVisibility(View.VISIBLE);
                     return billPay.isPayable();
                  }

                  @Override
                  public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
                     final int itemId = menuItem.getItemId();
                     if (itemId == R.id.miDeletePayment) {
                        deletePayment(billPay);
                        return true;
                     } else if (itemId == R.id.miPayNow) {
                        payNow(billPay);
                        return true;
                     }

                     return false;
                  }

                  @Override
                  public void onDestroyActionMode(ActionMode actionMode) {
                     lvPending.setItemChecked(position, false);
                     rlReference.setVisibility(View.GONE);
                     currentActionMode = null;
                  }
               });
            }
         });


         return convertView;
      }

   }

   private void payNow(final BillPay billPay) {
      // revive the Bill (no matter if pending or expired, to refresh the exchange rate
      // and restart the timer
      cs.reviveBillPay(billPay.id)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(new Observer<BillPay>() {
               @Override
               public void onCompleted() {
               }

               @Override
               public void onError(Throwable e) {
               }

               @Override
               public void onNext(BillPay billPay) {
                  if (billPay.details.amountToDeposit.compareTo(BigDecimal.ZERO) == 0) {
                     new Toaster(getActivity()).toast(getResources().getString(R.string.cashila_already_paid), false);
                  } else {
                     eventBus.post(billPay);
                  }
               }
            });
   }

   private void deletePayment(final BillPay billPay) {
      final AlertDialog.Builder deleteDialog = new AlertDialog.Builder(getActivity());
      deleteDialog.setTitle(getResources().getString(R.string.cashila_pending_payment));
      deleteDialog.setMessage(getResources().getString(R.string.cashila_are_you_sure_to_delete_payment));
      deleteDialog.setPositiveButton(getResources().getString(R.string.cashila_button_delete), new DialogInterface.OnClickListener() {
         @Override
         public void onClick(DialogInterface dialog, int which) {
            cs.deleteBillPay(billPay.id)
                  .observeOn(AndroidSchedulers.mainThread())
                  .subscribe(new Observer<List<Void>>() {
                     @Override
                     public void onCompleted() {
                        // reload the list
                        getBillPays(true, false);
                     }

                     @Override
                     public void onError(Throwable e) {
                        // something happened - reload the list
                        getBillPays(true, false);
                     }

                     @Override
                     public void onNext(List<Void> aVoid) {

                     }
                  });

         }
      });
      deleteDialog.setNegativeButton(getResources().getString(R.string.no), new DialogInterface.OnClickListener() {
         @Override
         public void onClick(DialogInterface dialog, int which) {
            dialog.dismiss();
         }
      });

      deleteDialog.create().show();
   }
}

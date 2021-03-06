package com.kenny.openimgur.fragments;

import android.app.Activity;
import android.app.Fragment;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import com.kenny.openimgur.R;
import com.kenny.openimgur.activities.FullScreenPhotoActivity;
import com.kenny.openimgur.activities.ViewActivity;
import com.kenny.openimgur.adapters.UploadAdapter;
import com.kenny.openimgur.api.ApiClient;
import com.kenny.openimgur.api.ImgurService;
import com.kenny.openimgur.api.responses.BasicResponse;
import com.kenny.openimgur.classes.FragmentListener;
import com.kenny.openimgur.classes.UploadedPhoto;
import com.kenny.openimgur.util.LogUtil;
import com.kenny.openimgur.util.SqlHelper;
import com.kenny.openimgur.util.ViewUtils;
import com.kennyc.view.MultiStateView;

import java.util.List;

import butterknife.BindView;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created by Kenny-PC on 1/14/2015.
 */
public class UploadedPhotosFragment extends BaseFragment implements View.OnClickListener, View.OnLongClickListener {
    @BindView(R.id.multiView)
    public MultiStateView mMultiStateView;

    @BindView(R.id.grid)
    public RecyclerView mGrid;

    @BindView(R.id.refreshLayout)
    protected SwipeRefreshLayout mRefreshLayout;

    private FragmentListener mListener;

    private UploadAdapter mAdapter;

    public static Fragment createInstance() {
        return new UploadedPhotosFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        if (activity instanceof FragmentListener) {
            mListener = (FragmentListener) activity;
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_uploads, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ViewUtils.setRecyclerViewGridDefaults(getActivity(), mGrid);
        if (mListener != null) mListener.onUpdateActionBarTitle(getString(R.string.uploaded_photos_title));
        mRefreshLayout.setColorSchemeColors(getResources().getColor(theme.accentColor));
        int bgColor = theme.isDarkTheme ? R.color.bg_dark : R.color.bg_light;
        mRefreshLayout.setProgressBackgroundColorSchemeColor(getResources().getColor(bgColor));
        mRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                mRefreshLayout.setRefreshing(true);
                refresh();
            }
        });
    }

    @Override
    public void onDestroyView() {
        if (mAdapter != null) mAdapter.onDestroy();
        super.onDestroyView();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.uploads, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                refresh();
                return true;
        }

        return super.onOptionsItemSelected(item);

    }

    @Override
    public void onResume() {
        super.onResume();
        if (mAdapter == null || mAdapter.isEmpty()) {
            refresh();
        }
    }

    @Override
    public void onClick(View v) {
        int adapterPosition = mGrid.getChildAdapterPosition(v);
        UploadedPhoto photo = mAdapter.getItem(adapterPosition);

        if (photo.isAlbum()) {
            startActivity(ViewActivity.createIntent(getActivity(), photo.getUrl(), true));
        } else {
            startActivity(FullScreenPhotoActivity.createIntent(getActivity(), photo.getUrl()));
        }
    }

    @Override
    public boolean onLongClick(View v) {
        int adapterPosition = mGrid.getChildAdapterPosition(v);
        final UploadedPhoto photo = mAdapter.getItem(adapterPosition);

        new AlertDialog.Builder(getActivity(), theme.getAlertDialogTheme())
                .setItems(R.array.uploaded_photos_options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // 0. Share 1. Copy Link 2. Delete

                        switch (which) {
                            case 0:
                                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                                shareIntent.setType("text/plain");
                                shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share));
                                shareIntent.putExtra(Intent.EXTRA_TEXT, photo.getUrl());
                                share(shareIntent, R.string.share);
                                break;

                            case 1:
                                ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                                clipboard.setPrimaryClip(ClipData.newPlainText("link", photo.getUrl()));
                                Snackbar.make(mListener != null ? mListener.getSnackbarView() : mMultiStateView, R.string.link_copied, Snackbar.LENGTH_LONG).show();
                                break;

                            case 2:
                                View deleteView = LayoutInflater.from(getActivity()).inflate(R.layout.upload_delete_confirm, null);
                                final CheckBox cb = (CheckBox) deleteView.findViewById(R.id.imgurDelete);
                                ((TextView) deleteView.findViewById(R.id.message)).setText(photo.isAlbum()
                                        ? R.string.uploaded_remove_album_message : R.string.uploaded_remove_photo_message);

                                new AlertDialog.Builder(getActivity(), theme.getAlertDialogTheme())
                                        .setNegativeButton(R.string.cancel, null)
                                        .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                if (cb.isChecked()) deleteItem(photo);
                                                SqlHelper.getInstance(getActivity()).deleteUploadedPhoto(photo);
                                                mAdapter.removeItem(photo);

                                                if (mAdapter.isEmpty()) {
                                                    mMultiStateView.setViewState(MultiStateView.VIEW_STATE_EMPTY);
                                                }
                                            }
                                        })
                                        .setView(deleteView)
                                        .show();
                        }
                    }
                }).show();
        return true;
    }

    private void deleteItem(@NonNull UploadedPhoto photo) {
        ImgurService apiService = ApiClient.getService();
        Call<BasicResponse> call;

        if (photo.isAlbum()) {
            call = apiService.deleteAlbum(photo.getDeleteHash());
        } else {
            call = apiService.deletePhoto(photo.getDeleteHash());
        }

        call.enqueue(new Callback<BasicResponse>() {
            @Override
            public void onResponse(Call<BasicResponse> call, Response<BasicResponse> response) {
                if (response != null && response.body() != null) {
                    LogUtil.v(TAG, "Response from deletion " + response.body().data);
                } else {
                    LogUtil.w(TAG, "Received no response when deleting");
                }
            }

            @Override
            public void onFailure(Call<BasicResponse> call, Throwable t) {
                LogUtil.e(TAG, "Unable to delete item", t);
            }
        });
    }

    private void refresh() {
        if (mAdapter != null) mAdapter.clear();
        mMultiStateView.setViewState(MultiStateView.VIEW_STATE_LOADING);
        List<UploadedPhoto> photos = SqlHelper.getInstance(getActivity()).getUploadedPhotos();

        if (!photos.isEmpty()) {
            if (mAdapter == null) {
                mAdapter = new UploadAdapter(getActivity(), photos, this, this);
                mGrid.setAdapter(mAdapter);
            } else {
                mAdapter.addItems(photos);
            }

            mMultiStateView.setViewState(MultiStateView.VIEW_STATE_CONTENT);
        } else {
            mMultiStateView.setViewState(MultiStateView.VIEW_STATE_EMPTY);
        }

        mRefreshLayout.setRefreshing(false);
    }
}

package nl.elastique.poetry.web.http;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import nl.elastique.poetry.core.annotations.Nullable;
import nl.elastique.poetry.web.http.exceptions.HttpStatusException;
import nl.elastique.poetry.core.concurrent.Callback;

/**
 * Wraps a request so it:
 *  - broadcasts event on begin, end, success and failure
 *  - executes in a background thread
 *  - provides a single success/failure callback in the foreground thread
 */
public class DeprecateRequestHandler
{
    public static class Broadcasts
    {
        // Broadcast that is sent when a request begins
        public static final String ACTION_BEGIN = "nl.elastique.poetry.web.http.HttpRequestHandler.BEGIN";
        // Broadcast that is sent when a request ends
        public static final String ACTION_END = "nl.elastique.poetry.web.http.HttpRequestHandler.END";
        // Broadcast that is sent when a request succeeds
        public static final String ACTION_SUCCESS = "nl.elastique.poetry.web.http.HttpRequestHandler.SUCCESS";
        // Broadcast that is sent when a request fails (and a HttpRequest is placed under the "error" intent data extra)
        public static final String ACTION_FAILURE = "nl.elastique.poetry.web.http.HttpRequestHandler.FAILURE";
    }

    private static final Logger sLogger = LoggerFactory.getLogger(DeprecateRequestHandler.class);

    private final HttpClient mHttpClient;

    private final HttpUriRequest mHttpRequest;

    public DeprecateRequestHandler(HttpUriRequest httpUriRequest)
    {
        this(new DefaultHttpClient(), httpUriRequest);
    }

    public DeprecateRequestHandler(HttpClient httpClient, HttpUriRequest httpRequest)
    {
        mHttpClient = httpClient;
        mHttpRequest = httpRequest;
    }

    /**
     *
     * @param context
     * @param callback optional callback
     */
    private void executeSynchronous(Context context, Callback<HttpResponse> callback)
    {
        try
        {
            broadcast(context, Broadcasts.ACTION_BEGIN);

            if (sLogger.isDebugEnabled())
            {
                sLogger.debug("started {} {}", mHttpRequest.getMethod(), mHttpRequest.getRequestLine().getUri());
            }

            HttpResponse response = mHttpClient.execute(mHttpRequest);

            if (response == null)
            {
                throw new IOException("no response from HttpRequest");
            }

            if (sLogger.isDebugEnabled())
            {
                sLogger.debug("finished {} {} (status code {})", mHttpRequest.getMethod(), mHttpRequest.getRequestLine().getUri(), response.getStatusLine().getStatusCode());
            }

            int status_code = response.getStatusLine().getStatusCode();

            if (status_code < 200 || status_code >= 300)
            {
                throw new HttpStatusException(response, status_code);
            }

            handleSuccess(context, callback, response);
        }
        catch (IOException | HttpStatusException e)
        {
            handleFailure(context, callback, e);
        }
        finally
        {
            broadcast(context, Broadcasts.ACTION_END);
        }
    }

    /**
     * Asynchronous execution.
     * @param context
     * @param listener will be called from a background thread
     */
    public void execute(final Context context, final Callback<HttpResponse> listener)
    {
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                executeSynchronous(context, listener);
            }
        }).start();
    }

    protected void handleFailure(final Context context, @Nullable final Callback<HttpResponse> callback, final Throwable throwable)
    {
        if (sLogger.isDebugEnabled())
        {
            String error_message = throwable != null ? throwable.getMessage() : "unknown error";
            sLogger.warn("failed: {} {} ({})", mHttpRequest.getMethod(), mHttpRequest.getRequestLine().getUri(), error_message);
        }

        if (callback != null)
        {
            callback.onFailure(throwable);
        }

        broadcast(context, Broadcasts.ACTION_FAILURE);
    }

    protected void handleSuccess(final Context context, @Nullable final Callback<HttpResponse> listener, @Nullable final HttpResponse response)
    {
        if (listener != null)
        {
            listener.onSuccess(response);
        }

        broadcast(context, Broadcasts.ACTION_SUCCESS);
    }

    private static void broadcast(Context context, String action)
    {
        Intent intent = (new Intent()).setAction(action);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }
}

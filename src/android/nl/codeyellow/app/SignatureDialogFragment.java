/**
 * Dialog to present the user with a "pad" upon which to place their
 * signature and an OK/Cancel button to commit or discard their
 * signature.  Embeds SignatureView as the signature area.
 */
package nl.codeyellow.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Base64;
import android.util.TypedValue;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.widget.RelativeLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import java.lang.CharSequence;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import nl.codeyellow.view.SignatureView;
import org.apache.cordova.CallbackContext; // Ugh, but the alternatives are probably worse
import org.apache.cordova.PluginResult;
import org.apache.cordova.CordovaWebView;

public class SignatureDialogFragment extends DialogFragment {
	protected CallbackContext callbackContext;
	protected CharSequence dialogTitle;
	protected CharSequence htmlString;
	protected AlertDialog dialog;
	
	public SignatureDialogFragment(CharSequence title, CharSequence html, CallbackContext ctx) {
		dialogTitle = title;
		callbackContext = ctx;
		htmlString = html;
	}

	// Closures are hard, so we jump through a few hoops and do it the Java way... The moronic way
	// (if there's a way to get at the dialog view from the title view I'd love to hear it:
	//  so far it didn't work because getParent keeps returning the Layout even if invoked
	//  on the parent etc)
	class DialogCloseListener implements View.OnClickListener {
		public AlertDialog dialog;
		public CallbackContext ctx;
		
		public DialogCloseListener(CallbackContext c) {
			ctx = c;
		}

		public void setDialog(AlertDialog d) {
			dialog = d;
		}
		
		@Override
		public void onClick(View view) {
			// Signal that the user has exited, just in
			// case we want to perform some sort of action
			// on the JS side.
			ctx.success((String)null);
			dialog.cancel();
		}
	}

	public void toggleOkButton(final boolean enabled) {
		final AlertDialog dialog = this.dialog;

		// More nonsense to please Android and avoid the obscure
		// message "Only the original thread that created a view
		// hierarchy can touch its views".
		this.getActivity().runOnUiThread(new Runnable() {
			public void run() {
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(enabled);
			}
		});
	}
	
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		Activity act = getActivity();
		final SignatureView signatureView = new SignatureView(act.getApplicationContext(), null);
		final CallbackContext ctx = callbackContext; // Silly Java

		// More silliness because the order of OK / Cancel keeps tripping people up,
		// so we present a "close" button at the top right and only use OK
		TextView titleLabelView = new TextView(act);
		titleLabelView.setText(dialogTitle);
		titleLabelView.setTextSize(TypedValue.COMPLEX_UNIT_MM, 5);
		titleLabelView.setPadding(15, 0, 0, 0);
		
		TextView titleCloseView = new TextView(act);
		titleCloseView.setText("╳");
		titleCloseView.setTextSize(TypedValue.COMPLEX_UNIT_MM, 5);
		RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
		params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
		titleCloseView.setLayoutParams(params);
		titleCloseView.setPadding(0, 0, 15, 0);
		DialogCloseListener listener = new DialogCloseListener(ctx);
		titleCloseView.setOnClickListener(listener);
		
		RelativeLayout titleView = new RelativeLayout(act);
		titleView.setGravity(Gravity.FILL_HORIZONTAL | Gravity.CENTER_VERTICAL);
		titleView.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.FILL_PARENT));
		titleView.addView(titleLabelView);
		titleView.addView(titleCloseView);

		RelativeLayout mainView = new RelativeLayout(act);
		mainView.setGravity(Gravity.FILL_HORIZONTAL | Gravity.CENTER_VERTICAL);
		mainView.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.FILL_PARENT));
		mainView.addView(signatureView);

		if (htmlString != null) {
			// XXX TODO: Find a way to use the same class as the
			// current Cordova Webview.  If we're using Crosswalk it
			// should automatically pick up that class.
			WebView htmlView = new WebView(act);
			WebSettings setting = htmlView.getSettings();
			setting.setJavaScriptEnabled(true);
			setting.setDefaultTextEncodingName("utf-8");
			htmlView.addJavascriptInterface(new SignatureDialogWebViewInterface(this),"SignatureDialog");
			// blah, blah, blah
			htmlView.setWebChromeClient(new WebChromeClient() {
				public boolean onConsoleMessage(ConsoleMessage cm) {
					Log.d("SignatureDialogFragment", cm.message() + " -- From line " + cm.lineNumber() + " of " + cm.sourceId());
					return true;
				}
			});
			
			htmlView.setLayerType(WebView.LAYER_TYPE_SOFTWARE, null); // FAST RENDERING, PLEASE (but will be slower when using fancy effects)
			// Nobody knows exactly how this works...
			htmlView.loadDataWithBaseURL("file:///android_asset/www/", htmlString.toString(), "text/html", null, null);
			mainView.addView(htmlView);
		}
		
		AlertDialog dialog = new AlertDialog.Builder(act)
			.setView(mainView)
			.setCustomTitle(titleView)
			.setPositiveButton(
				android.R.string.ok,
				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						Bitmap bmp = signatureView.getBitmap();
						// Drawing nothing is the same as canceling (for now?)
						if (bmp == null) {
							ctx.success((String)null);
						} else {
							// Maybe use getAllocationByteCount()+8?  It
							// was added in API level 19.
							int size = bmp.getWidth() * bmp.getHeight() * 4 + 8;
							ByteBuffer buf = ByteBuffer.allocate(size); // BIG_ENDIAN
							bmp.copyPixelsToBuffer(buf);
							
							// We can't put the metadata at the start because
							// copyPixelsToBuffer() ignores buf's position...
							buf.putInt(bmp.getWidth());
							buf.putInt(bmp.getHeight());
							ctx.success(buf.array());
						}
						dialog.dismiss();
					}
				})
			.create();
		// Needed for bridge interface
		this.dialog = dialog;
		listener.setDialog(dialog);
		return dialog;
	}
}

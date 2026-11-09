package com.ekta.sos

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.tasks.await

/**
 * Login with Firebase Auth (free): email + password, or Google.
 * Both switches must be on in Firebase console → Authentication → Sign-in method.
 */
object Account {
    /** Thrown with a ready-to-show string resource. */
    class Failure(val msg: Int) : Exception()

    private fun auth(ctx: Context): FirebaseAuth? =
        if (SosRepository.isFirebaseConfigured(ctx)) FirebaseAuth.getInstance() else null

    /** Without Firebase set up (a fresh clone with no google-services.json) there is nothing to log in to. */
    fun isSignedIn(ctx: Context): Boolean = auth(ctx)?.let { it.currentUser != null } ?: true

    /** Email (or name) of the person logged in, for Settings. */
    fun who(ctx: Context): String? = auth(ctx)?.currentUser?.let { it.email ?: it.displayName }

    suspend fun logIn(ctx: Context, email: String, password: String) = run {
        auth(ctx)!!.signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun signUp(ctx: Context, name: String, email: String, password: String) = run {
        val user = auth(ctx)!!.createUserWithEmailAndPassword(email.trim(), password).await().user
        user?.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build())?.await()
    }

    suspend fun resetPassword(ctx: Context, email: String) = run {
        auth(ctx)!!.sendPasswordResetEmail(email.trim()).await()
    }

    /** Opens Google's account chooser. Returns false if she closed it. */
    suspend fun google(activity: Activity): Boolean {
        // Made by the google-services plugin once Google login is on and google-services.json is re-downloaded.
        val id = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
        if (id == 0) throw Failure(R.string.login_err_google)
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(activity.getString(id)).build())
            .build()
        val cred = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (_: GetCredentialCancellationException) {
            return false
        } catch (e: Exception) {
            throw Failure(R.string.login_err_google)
        }
        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
        run { auth(activity)!!.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await() }
        return true
    }

    suspend fun logOut(ctx: Context) {
        auth(ctx)?.signOut()
        try { CredentialManager.create(ctx).clearCredentialState(ClearCredentialStateRequest()) } catch (_: Exception) {}
    }

    /** Turns Firebase errors into one plain sentence. */
    private inline fun <T> run(block: () -> T): T = try {
        block()
    } catch (e: Failure) {
        throw e
    } catch (e: FirebaseNetworkException) {
        throw Failure(R.string.login_err_net)
    } catch (e: FirebaseAuthWeakPasswordException) {
        throw Failure(R.string.login_err_short)
    } catch (e: FirebaseAuthUserCollisionException) {
        throw Failure(R.string.login_err_exists)
    } catch (e: FirebaseAuthInvalidUserException) {
        throw Failure(R.string.login_err_wrong)
    } catch (e: FirebaseAuthInvalidCredentialsException) {
        throw Failure(if (e.errorCode == "ERROR_INVALID_EMAIL") R.string.login_err_email else R.string.login_err_wrong)
    } catch (e: FirebaseAuthException) {
        throw Failure(if (e.errorCode == "ERROR_OPERATION_NOT_ALLOWED") R.string.login_err_off else R.string.login_err_other)
    } catch (e: Exception) {
        throw Failure(R.string.login_err_other)
    }
}

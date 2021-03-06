/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.view.fragment.dialogFragment;


import android.support.annotation.IntDef;

public final class PaymentConfirmationType {
    @IntDef({TOSHI_PAYMENT, TOSHI_PAYMENT_REQUEST, EXTERNAL, WEB})
    public @interface Type {}
    public static final int TOSHI_PAYMENT = 1;
    public static final int TOSHI_PAYMENT_REQUEST = 2;
    public static final int EXTERNAL = 3;
    public static final int WEB = 4;
    public static final int TOKEN_PAYMENT = 5;
}

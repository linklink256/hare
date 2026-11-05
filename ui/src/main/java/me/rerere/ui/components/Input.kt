package me.rerere.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue

@Composable
fun <T : Number> OutlinedNumberInput(
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    NumberInputField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
    ) { fieldValue, onFieldValueChange, isError, focusModifier ->
        OutlinedTextField(
            modifier = focusModifier,
            value = fieldValue,
            onValueChange = onFieldValueChange,
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = isError,
            colors = colors,
        )
    }
}

@Composable
fun <T : Number> NumberInput(
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    colors: TextFieldColors = TextFieldDefaults.colors()
) {
    NumberInputField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
    ) { fieldValue, onFieldValueChange, isError, focusModifier ->
        TextField(
            modifier = focusModifier,
            value = fieldValue,
            onValueChange = onFieldValueChange,
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = isError,
            colors = colors,
        )
    }
}

/**
 * 数字输入框的公共逻辑：编辑时保留光标与选区，只有能解析成数字才回写。
 *
 * 之前把字符串状态以 value 为 key 重建，每敲一个字都会重置光标到最前面；清空时又没有
 * 合法值可回写，输入框会卡在旧值/空值之间。现在只在**失焦**时把显示同步成外部值
 * （包括页面钳制后的结果），输入过程中不覆盖用户正在编辑的文本。
 */
@Composable
private fun <T : Number> NumberInputField(
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier,
    field: @Composable (
        value: TextFieldValue,
        onValueChange: (TextFieldValue) -> Unit,
        isError: Boolean,
        focusModifier: Modifier,
    ) -> Unit,
) {
    var fieldValue by remember { mutableStateOf(value.toFieldValue()) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(value, focused) {
        if (!focused && fieldValue.text != value.toString()) {
            fieldValue = value.toFieldValue()
        }
    }

    val isError = fieldValue.text.isNotEmpty() && !fieldValue.text.isValidNumberInput()
    field(
        fieldValue,
        { newValue ->
            // 先原样保留输入（含光标位置），再尝试解析；解析失败就让用户继续改
            fieldValue = newValue
            newValue.text.parseNumberOrNull(value)?.let(onValueChange)
        },
        isError,
        modifier.onFocusChanged { focused = it.isFocused },
    )
}

private fun Number.toFieldValue(): TextFieldValue {
    val text = toString()
    return TextFieldValue(text = text, selection = TextRange(text.length))
}

private fun <T : Number> String.parseNumberOrNull(reference: T): T? {
    if (!isValidNumberInput()) return null
    return try {
        @Suppress("UNCHECKED_CAST")
        when (reference) {
            is Int -> toInt() as T
            is Float -> toFloat() as T
            is Double -> toDouble() as T
            else -> null
        }
    } catch (e: NumberFormatException) {
        null
    }
}

private val NumberRegex = Regex("^[+-]?\\d+(\\.\\d+)?$")
private fun String.isValidNumberInput() = this.isNotEmpty() && NumberRegex.matches(this)

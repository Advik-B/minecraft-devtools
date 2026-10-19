export interface ToastMessage {
  id: string
  message: string
  type: 'info' | 'success' | 'warning' | 'error'
}

let toasts = $state<ToastMessage[]>([])

export const toastStore = {
  get toasts() {
    return toasts
  },
  show(message: string, type: 'info' | 'success' | 'warning' | 'error' = 'info', duration = 7000) {
    const id = Math.random().toString(36).substring(2, 9)
    toasts = [...toasts, { id, message, type }]

    setTimeout(() => {
      toasts = toasts.filter((t) => t.id !== id)
    }, duration)
  },
  dismiss(id: string) {
    toasts = toasts.filter((t) => t.id !== id)
  }
}

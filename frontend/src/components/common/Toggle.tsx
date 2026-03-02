interface ToggleProps {
  checked: boolean
  onChange: (checked: boolean) => void
  disabled?: boolean
  label?: string
}

export function Toggle({ checked, onChange, disabled, label }: ToggleProps) {
  return (
    <label className={`flex items-center gap-2 ${disabled ? 'opacity-40 cursor-not-allowed' : 'cursor-pointer'}`}>
      <div
        className={`relative w-10 h-5 rounded-full transition-colors ${
          checked ? 'bg-blue-500' : 'bg-[#2d3748]'
        }`}
        onClick={() => !disabled && onChange(!checked)}
      >
        <div
          className={`absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform ${
            checked ? 'translate-x-5' : 'translate-x-0'
          }`}
        />
      </div>
      {label && <span className="text-sm text-gray-300">{label}</span>}
    </label>
  )
}

interface BadgeProps {
  label: string
  color?: 'blue' | 'green' | 'red' | 'yellow' | 'purple'
}

const colorMap = {
  blue: 'bg-blue-500/20 text-blue-400 border-blue-500/30',
  green: 'bg-green-500/20 text-green-400 border-green-500/30',
  red: 'bg-red-500/20 text-red-400 border-red-500/30',
  yellow: 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30',
  purple: 'bg-purple-500/20 text-purple-400 border-purple-500/30',
}

export function Badge({ label, color = 'blue' }: BadgeProps) {
  return (
    <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded border ${colorMap[color]} uppercase tracking-wide`}>
      {label}
    </span>
  )
}
